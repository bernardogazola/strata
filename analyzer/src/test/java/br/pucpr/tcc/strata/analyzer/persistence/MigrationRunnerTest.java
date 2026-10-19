package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MigrationRunnerTest {

  private static final Path MIGRATIONS_DIRECTORY =
      Path.of("src", "main", "resources", "db", "migration");

  private static final Migrations.Migration V001 =
      new Migrations.Migration(1, "initial_schema", "/db/migration/V001__initial_schema.sql");

  private static final Migrations.Migration V002_BROKEN =
      new Migrations.Migration(2, "broken", "/db/migration-test/V002__broken.sql");

  @TempDir private Path temp;

  private Path database;
  private final InstantSource clock = TestCaches.steppingClock();

  @BeforeEach
  void arquivo() throws Exception {
    database = temp.toRealPath().resolve("analysis.sqlite");
  }

  private Connection escritor() throws SQLException {
    final Connection connection = TestCaches.raw(database);
    for (final String pragma :
        List.of(
            "PRAGMA auto_vacuum = INCREMENTAL",
            "PRAGMA journal_mode = WAL",
            "PRAGMA foreign_keys = ON")) {
      try (PreparedStatement statement = connection.prepareStatement(pragma)) {
        statement.execute();
      }
    }
    return connection;
  }

  private static void executar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  @Test
  void migraDoZeroComUmaLinhaDeLedgerCujoChecksumEOSha256DoRecurso() throws Exception {
    final byte[] arquivo =
        Files.readAllBytes(MIGRATIONS_DIRECTORY.resolve("V001__initial_schema.sql"));

    try (Connection writer = escritor()) {
      assertThat(Migrations.bundled().migrate(writer, clock)).isEqualTo(1);

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("1");
      assertThat(
              TestCaches.column(
                  writer, "SELECT version || '|' || name || '|' || checksum FROM schema_migration"))
          .containsExactly("1|initial_schema|" + TestCaches.sha256(arquivo));
      assertThat(TestCaches.userTables(writer)).hasSize(29);
      assertThat(TestCaches.strictTables(writer)).isEqualTo(29);
      assertThat(TestCaches.scalar(writer, "PRAGMA auto_vacuum")).isEqualTo("2");
      assertThat(writer.getAutoCommit()).as("o executor devolve a conexão em autocommit").isTrue();
    }
  }

  @Test
  void rodarDeNovoNaoFazNada() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      final String antes = TestCaches.scalar(writer, "SELECT applied_at FROM schema_migration");

      assertThat(Migrations.bundled().migrate(writer, clock)).isEqualTo(1);

      assertThat(TestCaches.scalar(writer, "SELECT count(*) FROM schema_migration")).isEqualTo("1");
      assertThat(TestCaches.scalar(writer, "SELECT applied_at FROM schema_migration"))
          .isEqualTo(antes);
    }
  }

  @Test
  void umaMigracaoAplicadaEditadaEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      executar(writer, "UPDATE schema_migration SET checksum = '" + "0".repeat(64) + "'");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(failure -> ((CacheDiscardException) failure).invalidation())
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umaLinhaDeLedgerQueFaltaEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      executar(writer, "DELETE FROM schema_migration");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(failure -> ((CacheDiscardException) failure).invalidation())
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umaVersaoMaisNovaEhDescartadaSemTentarRebaixar() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      executar(writer, "PRAGMA user_version = 99");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(failure -> ((CacheDiscardException) failure).invalidation())
          .isEqualTo(new CacheInvalidation.SchemaVersion(99, 1));

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version"))
          .as("nada foi rebaixado nem apagado: quem decide é a reconstrução")
          .isEqualTo("99");
      assertThat(TestCaches.userTables(writer)).hasSize(29);
    }
  }

  @Test
  void umSchemaNaoVazioNaVersaoZeroEhUmBancoAlheioEEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "CREATE TABLE analysis (alien TEXT)");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(failure -> ((CacheDiscardException) failure).invalidation())
          .isEqualTo(new CacheInvalidation.Corrupt());

      assertThat(TestCaches.userTables(writer)).containsExactly("analysis");
    }
  }

  @Test
  void umaMigracaoQueFalhaNumCacheExistenteNaoDeixaNadaEPedeReconstrucao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);

      assertThatThrownBy(() -> Migrations.of(List.of(V001, V002_BROKEN)).migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(failure -> ((CacheDiscardException) failure).invalidation())
          .isEqualTo(new CacheInvalidation.SchemaVersion(1, 2));

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("1");
      assertThat(TestCaches.scalar(writer, "SELECT count(*) FROM schema_migration")).isEqualTo("1");
      assertThat(TestCaches.userTables(writer))
          .as("as duas primeiras instruções do V002 rodaram e foram desfeitas com a terceira")
          .doesNotContain("migration_probe", "migration_broken");
      assertThat(writer.getAutoCommit()).isTrue();
    }
  }

  @Test
  void umaMigracaoQueFalhaDesdeOZeroEhDefeitoENaoPedeReconstrucao() throws Exception {
    try (Connection writer = escritor()) {
      assertThatThrownBy(() -> Migrations.of(List.of(V001, V002_BROKEN)).migrate(writer, clock))
          .isInstanceOf(SQLException.class)
          .isNotInstanceOf(PragmaRefusedException.class);

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("1");
      assertThat(TestCaches.userTables(writer)).doesNotContain("migration_probe");
    }
  }

  @Test
  void aListaDeMigracoesEhExatamenteOQueMoraEmDbMigrationCrescenteESemLacuna() throws Exception {
    final List<String> noDisco;
    try (Stream<Path> files = Files.list(MIGRATIONS_DIRECTORY)) {
      noDisco = files.map(path -> path.getFileName().toString()).sorted().toList();
    }

    final List<Migrations.Migration> lista = Migrations.bundled().migrations();

    assertThat(lista.stream().map(m -> Path.of(m.resourcePath()).getFileName().toString()).toList())
        .isEqualTo(noDisco);
    for (int index = 0; index < lista.size(); index++) {
      final Migrations.Migration migration = lista.get(index);
      assertThat(migration.version()).isEqualTo(index + 1);
      assertThat(migration.checksum())
          .as("o recurso do classpath é o arquivo do disco, byte a byte")
          .isEqualTo(
              TestCaches.sha256(
                  Files.readAllBytes(MIGRATIONS_DIRECTORY.resolve(noDisco.get(index)))));
    }
    assertThat(Migrations.bundled().latestVersion()).isEqualTo(lista.size());
  }
}
