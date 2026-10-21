package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.LAYOUT_DISCOVERY;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RegistryBindTest {

  private static final String ANOTHER_ROOT_COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @TempDir Path dados;
  @TempDir Path repositorios;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private final List<AnalysisRegistry> registros = new ArrayList<>();
  private final List<CountDownLatch> trincos = new ArrayList<>();

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void soltarEFechar() {
    trincos.forEach(CountDownLatch::countDown);
    registros.forEach(AnalysisRegistry::close);
  }

  private AnalysisRegistry registro(StagePlan plano) throws IOException {
    final AnalysisRegistry registro =
        new AnalysisRegistry(RunFixtures.settings(dados, quadros, plano, ManualNanoClock.frozen()));
    registros.add(registro);
    return registro;
  }

  private CountDownLatch trinco() {
    final CountDownLatch trinco = new CountDownLatch(1);
    trincos.add(trinco);
    return trinco;
  }

  @Test
  void umaIdentidadeDiferenteSobAMesmaChaveERecusada() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AnalysisRegistry registro = registro(RunFixtures.lightPlan());
    final RepositoryIdentity aberta =
        RepositoryIdentity.of(
            repositorio.toString(), PathFlavor.host(), RunFixtures.multiModuleCommit());
    final RepositoryIdentity colidente =
        new RepositoryIdentity(aberta.key(), aberta.pathFingerprint(), ANOTHER_ROOT_COMMIT);
    final RunEvents eventos =
        new RunEvents(RunFixtures.FIXTURE_ID, quadros, CancellationToken.create());

    final RepositoryCache cache = registro.cacheFor(aberta, repositorio, eventos);

    assertThatThrownBy(() -> registro.cacheFor(colidente, repositorio, eventos))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("colide com outra identidade");
    assertThat(registro.cacheFor(aberta, repositorio, eventos))
        .as("a recusa não trocou o cache aberto da chave")
        .isSameAs(cache);
  }

  private static boolean bloqueadaEm(Thread thread, Object lock) {
    if (thread.getState() != Thread.State.BLOCKED) {
      return false;
    }
    final LockInfo esperado =
        ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId()).getLockInfo();
    return esperado != null && esperado.getIdentityHashCode() == System.identityHashCode(lock);
  }

  private static boolean ate(BooleanSupplier condicao) {
    final CountDownLatch nunca = new CountDownLatch(1);
    final long prazo = System.nanoTime() + AWAIT.toNanos();
    while (!condicao.getAsBoolean()) {
      if (System.nanoTime() > prazo) {
        return false;
      }
      awaitLatch(nunca, Duration.ofMillis(5));
    }
    return true;
  }

  @Test
  void fecharContraALigacaoTerminaCanceladoNuncaComoDefeito() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final CountDownLatch antesDaLigacao = new CountDownLatch(1);
    final CountDownLatch seguir = trinco();
    quadros.observe(
        quadro -> {
          if ("analysis.stage.completed".equals(quadro.event())
              && LAYOUT_DISCOVERY.name().equals(quadro.stage())) {
            antesDaLigacao.countDown();
            awaitLatch(seguir, AWAIT);
          }
        });
    final CountDownLatch noEstagio = new CountDownLatch(1);
    final AnalysisRegistry registro =
        registro(
            RunFixtures.lightPlan()
                .with(STRUCTURAL_GRAPH, RunFixtures.pollingUntilCancelled(noEstagio, AWAIT)));
    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(antesDaLigacao, AWAIT)).isTrue();

    final Thread fechador = new Thread(registro::close, "fechador-do-teste");
    synchronized (registro.slot) {
      fechador.start();
      assertThat(ate(() -> bloqueadaEm(fechador, registro.slot)))
          .as("o close() parou no monitor da vaga")
          .isTrue();
      seguir.countDown();
      assertThat(
              ate(
                  () ->
                      noEstagio.getCount() == 0
                          || registro.status(id).orElseThrow().status() != AnalysisStatus.RUNNING))
          .as("a análise passou pela ligação, ou terminou nela")
          .isTrue();
    }
    fechador.join(AWAIT.toMillis());

    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    assertThat(terminal.event())
        .as("um fechamento que corre contra a ligação cancela; nunca é um defeito: %s", terminal)
        .isEqualTo("analysis.cancelled");
    assertThat(fechador.isAlive()).isFalse();
  }
}
