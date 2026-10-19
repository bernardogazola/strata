package br.pucpr.tcc.strata.analyzer.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

final class CacheQuarantineTest {

  private static final long NOW = 1_792_000_000_000L;

  @TempDir private Path temp;

  private Path directory;
  private Path database;

  @BeforeEach
  void pasta() throws Exception {
    directory = Files.createDirectories(temp.toRealPath().resolve(TestCaches.V01_KEY));
    database = directory.resolve(RepositoryCache.DATABASE_FILE);
  }

  private Path bak(long epoch) {
    return directory.resolve("analysis.sqlite." + epoch + ".bak");
  }

  private static InstantSource em(long millis) {
    return InstantSource.fixed(Instant.ofEpochMilli(millis));
  }

  private static void executar(Connection connection, String... sqls) throws SQLException {
    for (final String sql : sqls) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.execute();
      }
    }
  }

  private static String lerSomenteLeitura(Path file, String sql) throws SQLException {
    final SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    try (Connection reader = config.createConnection("jdbc:sqlite:" + file)) {
      return TestCaches.scalar(reader, sql);
    }
  }

  @Test
  void umWalComQuadrosViraOParDoBakByteAByteEOShmEApagado() throws Exception {
    final Path vivo =
        Files.createDirectories(temp.resolve("vivo")).resolve(RepositoryCache.DATABASE_FILE);
    try (Connection raw = TestCaches.raw(vivo)) {
      executar(
          raw,
          "PRAGMA journal_mode = WAL",
          "PRAGMA wal_autocheckpoint = 0",
          "CREATE TABLE t (x TEXT)",
          "INSERT INTO t (x) VALUES ('so-no-wal')");
      for (final String suffix : List.of("", "-wal", "-shm")) {
        Files.copy(TestCaches.sibling(vivo, suffix), TestCaches.sibling(database, suffix));
      }
    }
    final byte[] bancoAntes = Files.readAllBytes(database);
    final byte[] walAntes = Files.readAllBytes(TestCaches.sibling(database, "-wal"));
    assertThat(walAntes).isNotEmpty();

    final Path backup = CacheRecovery.quarantine(database, em(NOW));

    assertThat(backup).isEqualTo(bak(NOW));
    assertThat(database).doesNotExist();
    assertThat(backup).hasBinaryContent(bancoAntes);
    assertThat(TestCaches.sibling(backup, "-wal")).hasBinaryContent(walAntes);
    assertThat(TestCaches.sibling(database, "-wal")).doesNotExist();
    assertThat(TestCaches.sibling(database, "-shm")).doesNotExist();
    assertThat(TestCaches.sibling(backup, "-shm")).as("um -shm nunca é guardado").doesNotExist();
    assertThat(lerSomenteLeitura(backup, "SELECT x FROM t"))
        .as("<bak>-wal é o nome em que o SQLite procura o -wal da cópia")
        .isEqualTo("so-no-wal");
  }

  @Test
  void umWalVazioEApagadoENaoViraPar() throws Exception {
    Files.write(database, new byte[] {1, 2, 3});
    Files.write(TestCaches.sibling(database, "-wal"), new byte[0]);

    final Path backup = CacheRecovery.quarantine(database, em(NOW));

    assertThat(backup).hasBinaryContent(new byte[] {1, 2, 3});
    assertThat(TestCaches.sibling(database, "-wal")).doesNotExist();
    assertThat(TestCaches.sibling(backup, "-wal")).doesNotExist();
  }

  @Test
  void noMesmoMilissegundoDaMaisNovaAEpocaEAMaisNovaMaisUm() throws Exception {
    Files.writeString(bak(NOW), "anterior", UTF_8);
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(NOW));

    assertThat(backup).isEqualTo(bak(NOW + 1)).hasContent("atual");
    assertThat(bak(NOW))
        .as("o ATOMIC_MOVE substituiria a cópia anterior sem erro no macOS (medido)")
        .hasContent("anterior");
  }

  @Test
  void umRelogioQueVoltouNaoDaANovaCopiaUmNomeMaisVelhoQueOdaMaisNova() throws Exception {
    Files.writeString(bak(NOW), "anterior", UTF_8);
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(1_700_000_000_000L));

    assertThat(backup).isEqualTo(bak(NOW + 1));
    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(NOW + 1), bak(NOW));
  }

  @Test
  void umRelogioDeAntesDe2001AindaDaUmNomeQueARetencaoLe() throws Exception {
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(5L));

    assertThat(backup).isEqualTo(bak(1_000_000_000_000L));
    assertThat(CacheRecovery.backups(directory)).containsExactly(backup);
  }

  @Test
  void umNomeTomadoDepoisDaListagemLevaAEpocaSeguinteSemSubstituirNada() throws Exception {
    Files.writeString(database, "atual", UTF_8);
    final InstantSource concorrente =
        () -> {
          try {
            Files.writeString(bak(NOW), "intruso", UTF_8);
          } catch (IOException failure) {
            throw new UncheckedIOException(failure);
          }
          return Instant.ofEpochMilli(NOW);
        };

    final Path backup = CacheRecovery.quarantine(database, concorrente);

    assertThat(backup).isEqualTo(bak(NOW + 1)).hasContent("atual");
    assertThat(bak(NOW)).hasContent("intruso");
  }

  @Test
  void aRetencaoOrdenaPelaEpocaDoNomeENuncaPeloMtime() throws Exception {
    final long velha = NOW;
    final long meio = NOW + 1_000;
    final long nova = NOW + 2_000;
    for (final long epoca : List.of(velha, meio, nova)) {
      Files.writeString(bak(epoca), "copia " + epoca, UTF_8);
      Files.write(TestCaches.sibling(bak(epoca), "-wal"), new byte[] {1});
    }
    Files.setLastModifiedTime(
        bak(nova), FileTime.fromMillis(velha - Duration.ofDays(30).toMillis()));
    Files.setLastModifiedTime(bak(velha), FileTime.fromMillis(nova + 1));

    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(nova), bak(meio), bak(velha));
    CacheRecovery.retain(directory, 2);

    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(nova), bak(meio));
    assertThat(TestCaches.sibling(bak(velha), "-wal")).as("o par sai com a cópia").doesNotExist();
    assertThat(TestCaches.sibling(bak(meio), "-wal")).exists();
    assertThat(TestCaches.sibling(bak(nova), "-wal")).exists();
  }

  @Test
  void aRetencaoApagaParesOrfaosENuncaTocaNoQueNaoENosso() throws Exception {
    final Path orfao = Files.write(TestCaches.sibling(bak(NOW), "-wal"), new byte[] {1});
    final Path orfaoShm = Files.write(TestCaches.sibling(bak(NOW + 1), "-shm"), new byte[] {1});
    final Path pastaComNomeDeCopia =
        Files.createDirectory(directory.resolve("analysis.sqlite.1111111111111.bak"));
    final Path pastaComNomeDePar =
        Files.createDirectory(directory.resolve("analysis.sqlite.2222222222222.bak-wal"));
    final Path curto = Files.writeString(directory.resolve("analysis.sqlite.123.bak"), "x");
    final Path alemDoLong =
        Files.writeString(directory.resolve("analysis.sqlite.99999999999999999999.bak"), "x");
    final Path lock = Files.write(directory.resolve(CacheLock.FILE_NAME), new byte[0]);
    final Path banco = Files.writeString(database, "vivo");

    CacheRecovery.retain(directory, 2);

    assertThat(orfao).doesNotExist();
    assertThat(orfaoShm).doesNotExist();
    assertThat(pastaComNomeDeCopia).isDirectory();
    assertThat(pastaComNomeDePar).isDirectory();
    assertThat(curto).exists();
    assertThat(alemDoLong).as("20 dígitos não cabem em um long: não é nosso").exists();
    assertThat(lock).exists();
    assertThat(banco).hasContent("vivo");
    assertThat(CacheRecovery.backups(directory)).isEmpty();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umLinkComNomeDeCopiaNaoECopiaENuncaEApagado() throws Exception {
    final Path alvo = Files.writeString(temp.resolve("fora-da-pasta"), "alheio");
    final Path link = Files.createSymbolicLink(bak(NOW), alvo);
    Files.writeString(bak(NOW + 1), "a", UTF_8);
    Files.writeString(bak(NOW + 2), "b", UTF_8);
    Files.writeString(bak(NOW + 3), "c", UTF_8);

    CacheRecovery.retain(directory, 2);

    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(NOW + 3), bak(NOW + 2));
    assertThat(link).isSymbolicLink();
    assertThat(alvo).hasContent("alheio");
  }

  @Test
  void nomesPlantadosComDezenoveDigitosNaoSaoNossosEARetencaoGuardaACopiaNova() throws Exception {
    final Path plantadaMaior =
        Files.writeString(directory.resolve("analysis.sqlite." + Long.MAX_VALUE + ".bak"), "x");
    final Path plantadaMenor =
        Files.writeString(
            directory.resolve("analysis.sqlite." + (Long.MAX_VALUE - 1) + ".bak"), "x");
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(NOW));
    CacheRecovery.retain(directory, 2, backup);

    assertThat(backup).isEqualTo(bak(NOW)).hasContent("atual");
    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(NOW));
    assertThat(plantadaMaior).hasContent("x");
    assertThat(plantadaMenor).hasContent("x");
  }

  @Test
  void aRetencaoNuncaApagaACopiaQueAQuarentenaAcabouDeFazer() throws Exception {
    Files.writeString(bak(NOW), "anterior", UTF_8);
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(NOW));
    CacheRecovery.retain(directory, 0, backup);

    assertThat(backup).isEqualTo(bak(NOW + 1)).hasContent("atual");
    assertThat(bak(NOW)).doesNotExist();
  }

  @Test
  void depoisDaUltimaEpocaDeTrezeDigitosACopiaFicaForaDaRetencaoENuncaEApagada() throws Exception {
    final long ultima = 9_999_999_999_999L;
    Files.writeString(bak(ultima), "plantada", UTF_8);
    Files.writeString(database, "atual", UTF_8);

    final Path backup = CacheRecovery.quarantine(database, em(NOW));
    CacheRecovery.retain(directory, 1, backup);

    assertThat(backup.getFileName()).hasToString("analysis.sqlite.10000000000000.bak");
    assertThat(backup).hasContent("atual");
    assertThat(CacheRecovery.backups(directory)).containsExactly(bak(ultima));
  }

  @Test
  void umaQuarentenaQueParaNoShmDepoisDeRenomearDizQueRenomeou() throws Exception {
    Files.writeString(database, "atual", UTF_8);
    final Path shm = Files.createDirectories(TestCaches.sibling(database, "-shm"));
    Files.writeString(shm.resolve("x"), "x", UTF_8);

    final Throwable falha = catchThrowable(() -> CacheRecovery.quarantine(database, em(NOW)));

    assertThat(falha).isInstanceOf(CacheRecovery.IncompleteQuarantineException.class);
    assertThat(((CacheRecovery.IncompleteQuarantineException) falha).backup())
        .isEqualTo(bak(NOW))
        .hasContent("atual");
    assertThat(database).doesNotExist();
  }
}
