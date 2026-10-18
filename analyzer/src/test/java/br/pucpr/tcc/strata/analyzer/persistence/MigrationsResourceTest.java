package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteException;

final class MigrationsResourceTest {

  private static final Path V001_FILE =
      Path.of("src", "main", "resources", "db", "migration", "V001__initial_schema.sql");

  private static final Migrations.Migration V001 =
      new Migrations.Migration(1, "initial_schema", "/db/migration/V001__initial_schema.sql");

  private static final Migrations.Migration V002_BROKEN =
      new Migrations.Migration(2, "broken", "/db/migration-test/V002__broken.sql");

  private static final int SQLITE_READONLY = 8;

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
      executar(connection, pragma);
    }
    return connection;
  }

  private static void executar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private static CacheInvalidation descarte(Throwable failure) {
    return ((CacheDiscardException) failure).invalidation();
  }

  @Test
  void oScriptSaoOsBytesDoArquivoEOChecksumEhHexMinusculoDe64() throws Exception {
    final byte[] noDisco = Files.readAllBytes(V001_FILE);

    final byte[] script = V001.script();

    assertThat(script).isEqualTo(noDisco);
    assertThat(V001.checksum()).matches("^[0-9a-f]{64}$").isEqualTo(TestCaches.sha256(noDisco));
  }

  @Test
  void umRecursoQueNaoEstaNoClasspathFalhaNomeandoOCaminho() {
    final Migrations.Migration ausente =
        new Migrations.Migration(2, "missing", "/db/migration-test/V002__missing.sql");

    assertThatThrownBy(ausente::script)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/db/migration-test/V002__missing.sql");
    assertThatThrownBy(ausente::checksum).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void umEscritorDentroDeUmaTransacaoEhRecusadoSemNadaAplicado() throws Exception {
    try (Connection writer = escritor()) {
      writer.setAutoCommit(false);

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(IllegalStateException.class);

      writer.rollback();
      writer.setAutoCommit(true);
      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("0");
      assertThat(TestCaches.userTables(writer)).isEmpty();
    }
  }

  @Test
  void umUserVersionNegativoNaoEhDeNenhumStrataEEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "PRAGMA user_version = -1");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umBancoAlheioNaVersaoUmSemLedgerEhCorrupcaoENaoDefeito() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "CREATE TABLE notes (body TEXT)");
      executar(writer, "PRAGMA user_version = 1");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());

      assertThat(TestCaches.userTables(writer)).containsExactly("notes");
    }
  }

  @Test
  void umLedgerDeOutroFormatoEhCorrupcaoENaoDefeito() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "CREATE TABLE schema_migration (version INTEGER PRIMARY KEY)");
      executar(writer, "INSERT INTO schema_migration (version) VALUES (1)");
      executar(writer, "PRAGMA user_version = 1");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umaLinhaDeLedgerAMaisEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      executar(
          writer,
          "INSERT INTO schema_migration (version, name, checksum, applied_at)"
              + " VALUES (2, 'extra', '"
              + "0".repeat(64)
              + "', 0)");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umNomeDiferenteNoLedgerEhCorrupcao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      executar(writer, "UPDATE schema_migration SET name = 'renamed'");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());
    }
  }

  @Test
  void umaTabelaAlheiaQueComecaComSqliteSemSublinhadoContaComoSchema() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "CREATE TABLE sqlitebrowser_rename (a TEXT)");

      assertThatThrownBy(() -> Migrations.bundled().migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .extracting(MigrationsResourceTest::descarte)
          .isEqualTo(new CacheInvalidation.Corrupt());

      assertThat(TestCaches.column(writer, "SELECT name FROM sqlite_master"))
          .containsExactly("sqlitebrowser_rename");
    }
  }

  @Test
  void asTabelasInternasDoSqliteNaoTornamAlheioUmBancoVazio() throws Exception {
    try (Connection writer = escritor()) {
      executar(writer, "ANALYZE");
      assertThat(TestCaches.column(writer, "SELECT name FROM sqlite_master"))
          .contains("sqlite_stat1");

      assertThat(Migrations.bundled().migrate(writer, clock)).isEqualTo(1);

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("1");
      assertThat(TestCaches.userTables(writer)).hasSize(28);
    }
  }

  @Test
  void umaMigracaoQueFalhaNumCacheExistenteLevaAFalhaOriginalComoCausaParaOLog() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);

      assertThatThrownBy(() -> Migrations.of(List.of(V001, V002_BROKEN)).migrate(writer, clock))
          .isInstanceOf(CacheDiscardException.class)
          .cause()
          .isInstanceOf(SQLiteException.class)
          .hasMessageContaining("BOGUSTYPE");
    }
  }

  @Test
  void umaFalhaDeAmbienteNumCacheExistenteSobeIntactaENaoPedeReconstrucao() throws Exception {
    try (Connection writer = escritor()) {
      Migrations.bundled().migrate(writer, clock);
      final SQLiteConfig somenteLeitura = new SQLiteConfig();
      somenteLeitura.setReadOnly(true);

      try (Connection leitor =
          DriverManager.getConnection("jdbc:sqlite:" + database, somenteLeitura.toProperties())) {
        assertThatThrownBy(() -> Migrations.of(List.of(V001, V002_BROKEN)).migrate(leitor, clock))
            .isInstanceOf(SQLiteException.class)
            .satisfies(
                failure ->
                    assertThat(((SQLiteException) failure).getResultCode().code & 0xFF)
                        .isEqualTo(SQLITE_READONLY));
        assertThat(leitor.getAutoCommit()).isTrue();
      }

      assertThat(TestCaches.scalar(writer, "PRAGMA user_version")).isEqualTo("1");
      assertThat(TestCaches.userTables(writer)).doesNotContain("migration_probe");
    }
  }
}
