package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryRecord;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class CacheRecoveryTest {

  private static final Path EVIDENCE = Path.of("target", "cache-recovery");

  @TempDir private Path temp;

  private Path repositoryRoot;
  private PersistenceRoot root;
  private final InstantSource clock = TestCaches.steppingClock();

  @BeforeEach
  void raiz() throws Exception {
    final Path real = temp.toRealPath();
    repositoryRoot = Files.createDirectories(real.resolve("repo"));
    root = PersistenceRoot.prepare(real.resolve("data"));
  }

  private CacheOpening abrir(PersistenceRoot persistence, RepositoryIdentity identity)
      throws CacheUnavailableException {
    return RepositoryCache.open(persistence, identity, repositoryRoot, "0.0.0-test", clock);
  }

  private static Path banco(PersistenceRoot persistence) {
    return persistence
        .directory()
        .resolve(TestCaches.V01_KEY)
        .resolve(RepositoryCache.DATABASE_FILE);
  }

  private static void criarEFechar(CacheOpening opening) {
    opening.cache().close();
  }

  @Test
  void oPrimeiroUsoCriaEMigraEReabrirReusa() throws Exception {
    final CacheOpening primeiro = abrir(root, TestCaches.identityV01());
    assertThat(primeiro).isInstanceOf(CacheOpening.Created.class);
    try (Connection raw = TestCaches.raw(banco(root))) {
      assertThat(TestCaches.scalar(raw, "PRAGMA user_version")).isEqualTo("1");
      assertThat(TestCaches.scalar(raw, "SELECT repository_key FROM cache_metadata"))
          .isEqualTo(TestCaches.V01_KEY);
    }
    criarEFechar(primeiro);

    final CacheOpening segundo = abrir(root, TestCaches.identityV01());
    assertThat(segundo).isInstanceOf(CacheOpening.Reused.class);
    assertThat(segundo.cache().uncleanShutdown()).isFalse();
    criarEFechar(segundo);
  }

  @Test
  void quinhentosEDozeBytesAleatoriosNoCabecalhoRenomeiamRecriamEMigramSemCair() throws Exception {
    TestCaches.deleteTree(EVIDENCE);
    final PersistenceRoot evidence = PersistenceRoot.prepare(EVIDENCE.toAbsolutePath());
    criarEFechar(abrir(evidence, TestCaches.identityV01()));
    final Path database = banco(evidence);

    TestCaches.overwrite(database, 0, 512, 22L);
    final byte[] estragado = Files.readAllBytes(database);

    final CacheOpening reaberto = abrir(evidence, TestCaches.identityV01());

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    final List<Path> copias = CacheRecovery.backups(database.getParent());
    assertThat(copias).hasSize(1);
    assertThat(copias.get(0).getFileName().toString())
        .matches("analysis\\.sqlite\\.\\d{13,}\\.bak");
    assertThat(Files.readAllBytes(copias.get(0)))
        .as("a quarentena guarda o arquivo como estava, para diagnóstico")
        .isEqualTo(estragado);
    reaberto.cache().close();

    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.userTables(raw)).hasSize(28);
      assertThat(TestCaches.strictTables(raw)).isEqualTo(28);
      assertThat(TestCaches.scalar(raw, "PRAGMA auto_vacuum")).isEqualTo("2");
      assertThat(TestCaches.scalar(raw, "PRAGMA integrity_check")).isEqualTo("ok");
    }
  }

  @Test
  void guardaNoMaximoDuasCopiasAsMaisNovasPelaEpocaDoNome() throws Exception {
    criarEFechar(abrir(root, TestCaches.identityV01()));
    final Path database = banco(root);
    for (int round = 0; round < 3; round++) {
      TestCaches.overwrite(database, 0, 512, round);
      final CacheOpening reaberto = abrir(root, TestCaches.identityV01());
      assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
      reaberto.cache().close();
    }

    final List<Path> copias = CacheRecovery.backups(database.getParent());
    assertThat(copias).hasSize(2);
    final long nova = epoca(copias.get(0));
    final long velha = epoca(copias.get(1));
    assertThat(nova).isGreaterThan(velha);
  }

  private static long epoca(Path backup) {
    final String name = backup.getFileName().toString();
    return Long.parseLong(
        name.substring("analysis.sqlite.".length(), name.length() - ".bak".length()));
  }

  @Test
  void umWalVelhoNuncaEReaplicadoNoCacheNovo() throws Exception {
    criarEFechar(abrir(root, TestCaches.identityV01()));
    final Path database = banco(root);
    final Path wal = TestCaches.sibling(database, "-wal");

    try (Connection raw = TestCaches.raw(database)) {
      for (final String sql :
          List.of(
              "PRAGMA journal_mode = WAL",
              "PRAGMA wal_autocheckpoint = 0",
              "UPDATE cache_metadata SET analyzer_version = 'stale'")) {
        try (PreparedStatement statement = raw.prepareStatement(sql)) {
          statement.execute();
        }
      }
      Files.copy(wal, temp.resolve("crash-wal"));
    }
    Files.copy(temp.resolve("crash-wal"), wal);
    TestCaches.overwrite(database, 0, 512, 7L);

    final CacheOpening reaberto = abrir(root, TestCaches.identityV01());

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    final String versao =
        reaberto
            .cache()
            .connections()
            .read(c -> TestCaches.scalar(c, "SELECT analyzer_version FROM cache_metadata"));
    final List<String> tabelas = reaberto.cache().connections().read(TestCaches::userTables);
    reaberto.cache().close();

    assertThat(versao)
        .as("montar o novo ao lado e movê-lo para o lugar reaplicaria o -wal velho nele (medido)")
        .isEqualTo("0.0.0-test");
    assertThat(tabelas).hasSize(28);
    assertThat(TestCaches.sibling(database, "-wal")).doesNotExist();
    assertThat(TestCaches.sibling(database, "-shm")).doesNotExist();
    final Path bak = CacheRecovery.backups(database.getParent()).get(0);
    assertThat(TestCaches.sibling(bak, "-shm")).doesNotExist();
  }

  @Test
  void umaVersaoMaisNovaDoSchemaReconstroiComoSchemaVersion() throws Exception {
    criarEFechar(abrir(root, TestCaches.identityV01()));
    try (Connection raw = TestCaches.raw(banco(root));
        PreparedStatement bump = raw.prepareStatement("PRAGMA user_version = 99")) {
      bump.execute();
    }

    final CacheOpening reaberto = abrir(root, TestCaches.identityV01());

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.SchemaVersion(99, 1));
    reaberto.cache().close();
  }

  @Test
  void umaColisaoDaChaveReconstroiPorqueOArquivoNaoEDesteRepositorio() throws Exception {
    final CacheOpening primeiro = abrir(root, TestCaches.identityV01());
    primeiro
        .cache()
        .connections()
        .write(
            c -> {
              RepositoryDao.upsert(
                  c,
                  new RepositoryRecord(
                      TestCaches.V01_KEY,
                      "acme-payments-legacy",
                      TestCaches.identityV01().pathFingerprint().value(),
                      TestCaches.V01_ROOT,
                      "main",
                      false,
                      BuildSystem.MAVEN,
                      1L,
                      1L));
              return null;
            });
    primeiro.cache().close();

    final RepositoryIdentity colidente =
        new RepositoryIdentity(
            TestCaches.identityV01().key(),
            PathFingerprint.derive("/Users/bob/other", PathFlavor.POSIX),
            TestCaches.V01_ROOT);
    final CacheOpening reaberto = abrir(root, colidente);

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    reaberto.cache().close();
  }

  @Test
  void umDanoNumaPaginaDeIndicePassaPelaAberturaLeveEOQuickCheckOAcha() throws Exception {
    final CacheOpening primeiro = abrir(root, TestCaches.identityV01());
    primeiro
        .cache()
        .connections()
        .write(
            c -> {
              try (PreparedStatement insert =
                  c.prepareStatement(
                      "INSERT INTO analysis (analysis_id, status, started_at, analyzer_version,"
                          + " schema_version, protocol_version, config_hash, config_json,"
                          + " include_tests, history_scope) VALUES (?, 'COMPLETED', ?, 'x', 1, 1,"
                          + " 'h', '{}', 0, 'ALL')")) {
                for (int index = 0; index < 4_000; index++) {
                  insert.setString(1, "an_%026d".formatted(index));
                  insert.setLong(2, index);
                  insert.addBatch();
                }
                insert.executeBatch();
              }
              return null;
            });
    final String leaf =
        primeiro
            .cache()
            .connections()
            .read(
                c ->
                    TestCaches.scalar(
                        c,
                        "SELECT pageno FROM dbstat WHERE name = 'idx_analysis_status'"
                            + " AND pagetype = 'leaf' ORDER BY pageno LIMIT 1"));
    final int pageSize =
        Integer.parseInt(
            primeiro.cache().connections().read(c -> TestCaches.scalar(c, "PRAGMA page_size")));
    primeiro.cache().close();
    TestCaches.overwrite(banco(root), (Long.parseLong(leaf) - 1) * pageSize + 8, 512, 11L);

    final CacheOpening reaberto = abrir(root, TestCaches.identityV01());

    assertThat(reaberto)
        .as("pragmas, schema_migration, identidade e sonda de escrita não tocam a página (medido)")
        .isInstanceOf(CacheOpening.Reused.class);
    assertThat(reaberto.cache().quickCheck()).isFalse();
    final CacheOpening.Rebuilt reconstruido = reaberto.cache().rebuildAfterCorruption();
    assertThat(reconstruido.invalidation()).isEqualTo(new CacheInvalidation.Corrupt());
    assertThat(reconstruido.cache().quickCheck()).isTrue();
    reconstruido.cache().close();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umArquivoSomenteLeituraEhRecusadoSemRenomearNada() throws Exception {
    criarEFechar(abrir(root, TestCaches.identityV01()));
    final Path database = banco(root);
    final byte[] antes = Files.readAllBytes(database);
    Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("r--r--r--"));
    try {
      assertThatThrownBy(() -> abrir(root, TestCaches.identityV01()))
          .isInstanceOf(CacheUnavailableException.class)
          .extracting(failure -> ((CacheUnavailableException) failure).kind())
          .isEqualTo(CacheUnavailableException.Kind.STORAGE);

      assertThat(CacheRecovery.backups(database.getParent()))
          .as("ambiente não é corrupção: renomear aqui entrava em laço (medido)")
          .isEmpty();
      assertThat(Files.readAllBytes(database)).isEqualTo(antes);
    } finally {
      Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void aPastaDosCachesDentroDoRepositorioAnalisadoEhRecusada() throws Exception {
    final Path home = temp.toRealPath().resolve("home");
    final PersistenceRoot inside =
        PersistenceRoot.prepare(home.resolve("Library").resolve("Strata"));

    assertThatThrownBy(
            () -> RepositoryCache.open(inside, TestCaches.identityV01(), home, "0.0.0-test", clock))
        .isInstanceOf(CacheUnavailableException.class)
        .extracting(failure -> ((CacheUnavailableException) failure).kind())
        .isEqualTo(CacheUnavailableException.Kind.LOCATION_INSIDE_REPOSITORY);
    assertThat(inside.directory().resolve(TestCaches.V01_KEY)).doesNotExist();
  }

  @Test
  void abrirDuasVezesOMesmoCacheNoMesmoProcessoEhDefeitoEnaoEspera() throws Exception {
    final CacheOpening primeiro = abrir(root, TestCaches.identityV01());
    try {
      assertThatThrownBy(() -> abrir(root, TestCaches.identityV01()))
          .as("um segundo canal sobre o lock o soltaria para o processo inteiro (POSIX)")
          .isInstanceOf(IllegalStateException.class);
    } finally {
      primeiro.cache().close();
    }
  }

  @Test
  void umLockSeguradoPorOutroProcessoEhRecusadoDepoisDaEspera() throws Exception {
    final Path directory = Files.createDirectories(temp.resolve("locked"));

    assertThatThrownBy(() -> CacheLock.acquire(directory, Duration.ofMillis(50), channel -> null))
        .isInstanceOf(CacheUnavailableException.class)
        .extracting(failure -> ((CacheUnavailableException) failure).kind())
        .isEqualTo(CacheUnavailableException.Kind.LOCKED);
  }
}
