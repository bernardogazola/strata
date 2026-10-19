package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryRecord;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class CacheOpenEdgeTest {

  private static final Migrations.Migration V001 =
      new Migrations.Migration(1, "initial_schema", "/db/migration/V001__initial_schema.sql");

  private static final Migrations.Migration V002_BROKEN =
      new Migrations.Migration(2, "broken", "/db/migration-test/V002__broken.sql");

  private static final Duration LIMITE = Duration.ofSeconds(10);

  @TempDir private Path temp;

  private Path real;
  private Path repositoryRoot;
  private PersistenceRoot root;
  private Path directory;
  private Path database;
  private final InstantSource clock = TestCaches.steppingClock();

  @BeforeEach
  void raiz() throws Exception {
    real = temp.toRealPath();
    repositoryRoot = Files.createDirectories(real.resolve("repo"));
    root = PersistenceRoot.prepare(real.resolve("data"));
    directory = root.directory().resolve(TestCaches.V01_KEY);
    database = directory.resolve(RepositoryCache.DATABASE_FILE);
  }

  private CacheOpening abrir() throws CacheUnavailableException {
    return RepositoryCache.open(
        root, TestCaches.identityV01(), repositoryRoot, "0.0.0-test", clock);
  }

  private void criarEFechar() throws CacheUnavailableException {
    abrir().cache().close();
  }

  private static CacheUnavailableException recusa(Throwable failure) {
    assertThat(failure).isInstanceOf(CacheUnavailableException.class);
    return (CacheUnavailableException) failure;
  }

  private List<String> nomesDeQuarentena() throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .map(entry -> entry.getFileName().toString())
          .filter(
              name -> name.startsWith(RepositoryCache.DATABASE_FILE + ".") && name.contains("bak"))
          .toList();
    }
  }

  private static void executar(Connection connection, String... sqls) throws SQLException {
    for (final String sql : sqls) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.execute();
      }
    }
  }

  private void migrarSemCacheMetadata() throws Exception {
    Files.createDirectories(directory);
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      connections.write(c -> null);
      connections.withWriter(writer -> Migrations.bundled().migrate(writer, clock));
    }
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void umaConexaoQueNaoFechaRecusaAReconstrucaoSemRenomearEPrendeOLock() throws Exception {
    final RepositoryCache cache = abrir().cache();
    final Connection envenenado =
        cache
            .connections()
            .read(
                c -> {
                  catchThrowable(() -> c.prepareStatement(""));
                  return c;
                });

    final CacheUnavailableException recusa = recusa(catchThrowable(cache::rebuildAfterCorruption));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(recusa.discarded()).as("nada foi renomeado").isEmpty();
    assertThat(nomesDeQuarentena()).isEmpty();
    assertThat(database).exists();
    assertThat(envenenado.isClosed()).isFalse();
    assertThat(cache.connections().allClosed()).isFalse();
    cache.close();
    assertThat(CacheLock.isHeldInThisProcess(directory))
        .as("soltá-lo deixaria outro processo renomear um arquivo ainda aberto aqui")
        .isTrue();
    assertThatThrownBy(this::abrir)
        .as("a chave não reabre nesta JVM: a falha segura")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void umaConexaoQueNaoFechaNoFechamentoDoCachePrendeOLockAteAJvmSair() throws Exception {
    final RepositoryCache cache = abrir().cache();
    final Connection envenenado =
        cache
            .connections()
            .read(
                c -> {
                  catchThrowable(() -> c.prepareStatement(""));
                  return c;
                });

    cache.close();

    assertThat(envenenado.isClosed()).isFalse();
    assertThat(cache.connections().allClosed()).isFalse();
    assertThat(CacheLock.isHeldInThisProcess(directory))
        .as("soltá-lo deixaria outro processo renomear um arquivo ainda aberto aqui")
        .isTrue();
    assertThatThrownBy(this::abrir).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void umLeitorPerdidoNumaUnidadeQueFalhouEhFechadoAntesDeOLockSerSolto() throws Exception {
    final RepositoryCache cache = abrir().cache();
    final AtomicReference<Connection> perdido = new AtomicReference<>();
    final Throwable falha =
        catchThrowable(
            () ->
                cache
                    .connections()
                    .read(
                        c -> {
                          perdido.set(c);
                          catchThrowable(() -> c.prepareStatement(""));
                          throw new SQLException("a unidade falhou depois do veneno");
                        }));
    assertThat(falha).hasMessage("a unidade falhou depois do veneno");
    assertThat(perdido.get().isClosed()).isFalse();

    cache.close();

    assertThat(perdido.get().isClosed()).isTrue();
    assertThat(CacheLock.isHeldInThisProcess(directory)).isFalse();
    TestCaches.overwrite(database, 0, 512, 22L);
    final CacheOpening reaberto = abrir();
    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    reaberto.cache().close();
  }

  @Test
  void umaCriacaoInterrompidaEntreOV001EACacheMetadataReabreComoCreated() throws Exception {
    migrarSemCacheMetadata();

    final CacheOpening reaberto = abrir();

    assertThat(reaberto)
        .as("não havia nada a invalidar: um aviso de corrupção aqui seria falso")
        .isInstanceOf(CacheOpening.Created.class);
    reaberto.cache().close();
    assertThat(nomesDeQuarentena()).isEmpty();
    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.scalar(raw, "SELECT repository_key FROM cache_metadata"))
          .isEqualTo(TestCaches.V01_KEY);
      assertThat(TestCaches.scalar(raw, "SELECT schema_version FROM cache_metadata"))
          .isEqualTo("1");
    }
    final CacheOpening depois = abrir();
    assertThat(depois).isInstanceOf(CacheOpening.Reused.class);
    depois.cache().close();
  }

  @Test
  void semCacheMetadataMasComUmRepositorioOArquivoContinuaCorrupcao() throws Exception {
    migrarSemCacheMetadata();
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      connections.write(
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
    }

    final CacheOpening reaberto = abrir();

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    reaberto.cache().close();
  }

  @Test
  void umaReconstrucaoQueFalhaDepoisDaQuarentenaLevaODescarteNaExcecao() throws Exception {
    criarEFechar();
    TestCaches.overwrite(database, 0, 512, 22L);
    final Path shm = Files.createDirectories(TestCaches.sibling(database, "-shm"));
    Files.writeString(shm.resolve("x"), "x");

    final CacheUnavailableException recusa = recusa(catchThrowable(this::abrir));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(recusa.discarded())
        .as("o arquivo velho já saiu: quem abriu ainda emite o cache.invalidated")
        .contains(new CacheInvalidation.Corrupt());
    assertThat(CacheRecovery.backups(directory)).hasSize(1);
    assertThat(CacheLock.isHeldInThisProcess(directory)).isFalse();

    Files.delete(shm.resolve("x"));
    Files.delete(shm);
    final CacheOpening seguinte = abrir();
    assertThat(seguinte)
        .as("por isso o descarte vai na exceção: a abertura seguinte não o relata")
        .isInstanceOf(CacheOpening.Created.class);
    seguinte.cache().close();
  }

  @Test
  void umaReconstrucaoCujaTentativaNovaFalhaLevaODescarteSchemaVersion() throws Exception {
    criarEFechar();
    final Migrations quebradas = Migrations.of(List.of(V001, V002_BROKEN));

    final CacheUnavailableException recusa =
        recusa(
            catchThrowable(
                () ->
                    RepositoryCache.open(
                        root,
                        TestCaches.identityV01(),
                        repositoryRoot,
                        "0.0.0-test",
                        clock,
                        quebradas)));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(recusa.discarded()).contains(new CacheInvalidation.SchemaVersion(1, 2));
    assertThat(CacheRecovery.backups(directory)).hasSize(1);
    final CacheOpening seguinte = abrir();
    assertThat(seguinte).isInstanceOf(CacheOpening.Created.class);
    seguinte.cache().close();
  }

  @Test
  void outraChaveEmCacheMetadataReconstroiComoCorrupt() throws Exception {
    criarEFechar();
    try (Connection raw = TestCaches.raw(database)) {
      executar(raw, "UPDATE cache_metadata SET repository_key = 'ffffffffffffffff'");
    }

    final CacheOpening reaberto = abrir();

    assertThat(reaberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) reaberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    reaberto.cache().close();
  }

  @Test
  void umWalComQuadrosSemDanoNoArquivoReusaEDizQueASessaoAnteriorNaoFechouLimpo() throws Exception {
    criarEFechar();
    final Path wal = TestCaches.sibling(database, "-wal");
    try (Connection raw = TestCaches.raw(database)) {
      executar(
          raw,
          "PRAGMA journal_mode = WAL",
          "PRAGMA wal_autocheckpoint = 0",
          "UPDATE cache_metadata SET analyzer_version = 'sessao-que-caiu'");
      Files.copy(wal, temp.resolve("crash-wal"));
    }
    Files.copy(temp.resolve("crash-wal"), wal);

    final CacheOpening reaberto = abrir();

    assertThat(reaberto).isInstanceOf(CacheOpening.Reused.class);
    assertThat(reaberto.cache().uncleanShutdown())
        .as("o sinal que decide se o quick_check roda")
        .isTrue();
    reaberto.cache().close();
    final CacheOpening limpo = abrir();
    assertThat(limpo.cache().uncleanShutdown())
        .as("o fechamento em ordem não deixa -wal")
        .isFalse();
    limpo.cache().close();
  }

  @Test
  void umaPastaNoLugarDoBancoEhRecusadaSemRenomearNada() throws Exception {
    final Path alheio = Files.writeString(Files.createDirectories(database).resolve("dentro"), "x");

    final CacheUnavailableException recusa = recusa(catchThrowable(this::abrir));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(recusa.discarded()).isEmpty();
    assertThat(database).isDirectory();
    assertThat(alheio).hasContent("x");
    assertThat(nomesDeQuarentena()).isEmpty();
    assertThat(CacheLock.isHeldInThisProcess(directory)).isFalse();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umLinkNoLugarDoBancoEhRecusadoSemSeguirNemRenomear() throws Exception {
    Files.createDirectories(directory);
    final Path alvo = Files.createFile(real.resolve("alvo-fora-da-pasta"));
    Files.createSymbolicLink(database, alvo);

    final CacheUnavailableException recusa = recusa(catchThrowable(this::abrir));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(alvo).isEmptyFile();
    assertThat(TestCaches.sibling(alvo, "-wal")).doesNotExist();
    assertThat(database).isSymbolicLink();
    assertThat(nomesDeQuarentena()).isEmpty();
    assertThat(CacheLock.isHeldInThisProcess(directory)).isFalse();
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "FIFO é POSIX")
  void umFifoNoLugarDoLockEhRecusadoComoStorageSemEsperar() throws Exception {
    Files.createDirectories(directory);
    TestFifos.create(directory.resolve(CacheLock.FILE_NAME));

    final Throwable falha = assertTimeoutPreemptively(LIMITE, () -> catchThrowable(this::abrir));

    assertThat(recusa(falha).kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(CacheLock.isHeldInThisProcess(directory)).isFalse();
    assertThat(database).doesNotExist();
  }

  @Test
  void umArquivoVazioDeSchemaJaEscritoSemAutoVacuumIncrementalEhRecriado() throws Exception {
    Files.createDirectories(directory);
    try (Connection raw = TestCaches.raw(database)) {
      executar(raw, "PRAGMA journal_mode = WAL");
    }
    assertThat(Files.size(database)).isPositive();

    final CacheOpening aberto = abrir();

    assertThat(aberto).isInstanceOf(CacheOpening.Rebuilt.class);
    assertThat(((CacheOpening.Rebuilt) aberto).invalidation())
        .isEqualTo(new CacheInvalidation.Corrupt());
    final String autoVacuum =
        aberto.cache().connections().write(c -> TestCaches.scalar(c, "PRAGMA auto_vacuum"));
    aberto.cache().close();
    assertThat(autoVacuum)
        .as("migrado assim, o incremental_vacuum não devolveria nada, nunca")
        .isEqualTo("2");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umLinkParaORepositorioNaoEscondeQueAPastaDosCachesFicaDentroDele() throws Exception {
    final Path home = Files.createDirectories(real.resolve("Home"));
    final PersistenceRoot dentro =
        PersistenceRoot.prepare(home.resolve("Library").resolve("Strata"));
    final Path link = Files.createSymbolicLink(real.resolve("home-link"), home);

    final CacheUnavailableException recusa =
        recusa(
            catchThrowable(
                () ->
                    RepositoryCache.open(
                        dentro, TestCaches.identityV01(), link, "0.0.0-test", clock)));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.LOCATION_INSIDE_REPOSITORY);
    assertThat(dentro.directory().resolve(TestCaches.V01_KEY)).doesNotExist();
  }

  @Test
  void umaRaizDeRepositorioQueNaoExisteEhStorageAntesDeCriarQualquerCoisa() {
    final CacheUnavailableException recusa =
        recusa(
            catchThrowable(
                () ->
                    RepositoryCache.open(
                        root,
                        TestCaches.identityV01(),
                        real.resolve("nao-existe"),
                        "0.0.0-test",
                        clock)));

    assertThat(recusa.kind()).isEqualTo(CacheUnavailableException.Kind.STORAGE);
    assertThat(directory).doesNotExist();
  }

  @Test
  void umErroNoMeioDaAberturaSoltaOLockEFechaAsConexoes() throws Exception {
    final InstantSource estoura =
        () -> {
          throw new StackOverflowError("probe");
        };

    final Throwable falha =
        catchThrowable(
            () ->
                RepositoryCache.open(
                    root, TestCaches.identityV01(), repositoryRoot, "0.0.0-test", estoura));

    assertThat(falha).isInstanceOf(StackOverflowError.class);
    assertThat(CacheLock.isHeldInThisProcess(directory))
        .as("preso no registro, o cache desta chave nunca mais abriria nesta JVM")
        .isFalse();
    final CacheOpening seguinte = abrir();
    assertThat(seguinte)
        .as("a migração foi desfeita e o escritor fechou: o arquivo segue vazio de schema")
        .isInstanceOf(CacheOpening.Created.class);
    seguinte.cache().close();
    assertThat(TestCaches.sibling(database, "-wal"))
        .as("só a última conexão a fechar apaga o -wal: um escritor vazado pelo Error o manteria")
        .doesNotExist();
  }
}
