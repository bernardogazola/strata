package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteConnectionsNestingTest {

  private static final Duration LIMITE = Duration.ofSeconds(10);

  private static final long NOT_YET_MILLIS = 300;

  private static final long DONE_SECONDS = 10;

  @TempDir private Path temp;

  private Path real;

  @BeforeEach
  void pasta() throws Exception {
    real = temp.toRealPath();
  }

  private SqliteConnections abrir() throws SQLException {
    return SqliteConnections.open(real.resolve(RepositoryCache.DATABASE_FILE));
  }

  private static ExecutorService daemons(int threads) {
    return Executors.newFixedThreadPool(
        threads,
        runnable -> {
          final Thread thread = new Thread(runnable, "nesting-test");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static String lido(SqliteConnections connections) throws SQLException {
    return connections.read(c -> TestCaches.scalar(c, "SELECT 1"));
  }

  private static String escrito(SqliteConnections connections) throws SQLException {
    return connections.write(c -> TestCaches.scalar(c, "SELECT 1"));
  }

  private static void aguardar(CountDownLatch latch) {
    try {
      if (!latch.await(DONE_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("o latch nunca foi liberado");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  void umaEscritaDentroDeUmaLeituraEhDefeitoENaoTravamento() throws Exception {
    try (SqliteConnections connections = abrir()) {
      assertTimeoutPreemptively(
          LIMITE,
          () -> {
            assertThatThrownBy(() -> connections.read(c -> connections.write(w -> "escrita")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dentro de uma unidade de leitura");
            assertThatThrownBy(() -> connections.read(c -> connections.withWriter(w -> "escrita")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dentro de uma unidade de leitura");
          });

      assertThat(escrito(connections))
          .as("a recusa não deixou licença nem lock para trás")
          .isEqualTo("1");
    }
  }

  @Test
  void umaEscritaDentroDeUmaLeituraNaoTravaContraUmFechamentoConcorrente() throws Exception {
    final SqliteConnections connections = abrir();
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch escrever = new CountDownLatch(1);
    final ExecutorService pool = daemons(2);
    try {
      assertTimeoutPreemptively(
          LIMITE,
          () -> {
            final Future<String> leitura =
                pool.submit(
                    () ->
                        connections.read(
                            c -> {
                              dentro.countDown();
                              aguardar(escrever);
                              return connections.write(w -> "nunca");
                            }));
            aguardar(dentro);
            final Future<Object> fechamento =
                pool.submit(
                    () -> {
                      connections.close();
                      return null;
                    });
            assertThatThrownBy(() -> fechamento.get(NOT_YET_MILLIS, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            escrever.countDown();

            assertThatThrownBy(() -> leitura.get(DONE_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            fechamento.get(DONE_SECONDS, TimeUnit.SECONDS);
          });

      assertThat(connections.allClosed()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void umaLeituraDentroDeOutraEhDefeitoENaoTravamento() throws Exception {
    try (SqliteConnections connections = abrir()) {
      assertTimeoutPreemptively(
          LIMITE,
          () ->
              assertThatThrownBy(() -> connections.read(c -> connections.read(inner -> "leitura")))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("rodando aqui"));

      assertThat(lido(connections)).isEqualTo("1");
    }
  }

  @Test
  void umaLeituraDentroDeUmaEscritaContinuaPermitida() throws Exception {
    try (SqliteConnections connections = abrir()) {
      final String lido =
          assertTimeoutPreemptively(
              LIMITE,
              () ->
                  connections.write(
                      w -> connections.read(c -> TestCaches.scalar(c, "SELECT 'leitura'"))));

      assertThat(lido).isEqualTo("leitura");
    }
  }

  @Test
  void fecharDeDentroDeUmaUnidadeEhDefeitoENaoMudaNada() throws Exception {
    final SqliteConnections connections = abrir();
    assertTimeoutPreemptively(
        LIMITE,
        () -> {
          assertThatThrownBy(
                  () ->
                      connections.read(
                          c -> {
                            connections.close();
                            return null;
                          }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("dentro de uma unidade de leitura");
          assertThatThrownBy(
                  () ->
                      connections.write(
                          c -> {
                            connections.close();
                            return null;
                          }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("dentro de uma unidade de trabalho na conexão de escrita");
          assertThatThrownBy(
                  () ->
                      connections.withWriter(
                          c -> {
                            connections.close();
                            return null;
                          }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("dentro de uma unidade de trabalho na conexão de escrita");

          assertThat(connections.allClosed())
              .as("a recusa vem antes da marca de fechado: as conexões seguem abertas e em uso")
              .isFalse();
          assertThat(lido(connections)).isEqualTo("1");
          assertThat(escrito(connections)).isEqualTo("1");
          connections.close();
        });
  }

  @Test
  void fecharOuReconstruirOCacheDeDentroDeUmaLeituraEhDefeitoENaoTravamento() throws Exception {
    final Path repositoryRoot = Files.createDirectories(real.resolve("repo"));
    final PersistenceRoot root = PersistenceRoot.prepare(real.resolve("data"));
    final InstantSource clock = TestCaches.steppingClock();
    final RepositoryCache cache =
        RepositoryCache.open(root, TestCaches.identityV01(), repositoryRoot, "0.0.0-test", clock)
            .cache();
    final Path directory = root.directory().resolve(TestCaches.V01_KEY);

    assertTimeoutPreemptively(
        LIMITE,
        () -> {
          final Throwable deDentro =
              cache.connections().read(c -> catchThrowable(cache::rebuildAfterCorruption));
          assertThat(deDentro).isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(
                  () ->
                      cache
                          .connections()
                          .read(
                              c -> {
                                cache.close();
                                return null;
                              }))
              .isInstanceOf(IllegalStateException.class);

          assertThat(CacheRecovery.backups(directory)).as("nada foi renomeado").isEmpty();
          assertThat(cache.quickCheck()).as("o cache segue aberto e em uso").isTrue();
          cache.close();
        });

    assertThat(CacheLock.isHeldInThisProcess(directory))
        .as("o fechamento de verdade, depois, ainda solta o lock")
        .isFalse();
  }
}
