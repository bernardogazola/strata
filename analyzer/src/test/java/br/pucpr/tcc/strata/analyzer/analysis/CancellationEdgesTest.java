package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.GIT_HISTORY_CHEAP;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.SOURCE_ANALYSIS;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CancellationEdgesTest {

  private static final Duration UNCANCELLED_LIMIT = Duration.ofSeconds(10);

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

  private String desfechoDoEstagio(AnalysisId id, AnalysisStage stage) {
    return quadros.events(id, "analysis.stage.completed").stream()
        .filter(quadro -> stage.name().equals(quadro.stage()))
        .map(quadro -> quadro.payload().path("status").asText())
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhum stage.completed de " + stage));
  }

  @Test
  void umEstagioQueFalhaDepoisDeUmCancelamentoSemGanchoTerminaCancelled() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch soltar = trinco();
    final AnalysisRegistry registro =
        registro(
            RunFixtures.lightPlan()
                .with(
                    STRUCTURAL_GRAPH,
                    context -> {
                      dentro.countDown();
                      awaitLatch(soltar, AWAIT);
                      throw new StageFailedException(
                          ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE));
                    }));
    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).isTrue();

    final AnalysisRegistry.CancelResult pedido = registro.cancel(id);
    assertThat(pedido.cancelled()).isTrue();
    soltar.countDown();

    assertThat(quadros.awaitTerminal(id, AWAIT).event()).isEqualTo("analysis.cancelled");
    assertThat(desfechoDoEstagio(id, STRUCTURAL_GRAPH))
        .as("o estágio que falhou depois do pedido termina CANCELLED, mesmo sem o gancho")
        .isEqualTo("CANCELLED");
    assertThat(registro.status(id).orElseThrow().stages().get(STRUCTURAL_GRAPH).status())
        .isEqualTo(StageStatus.CANCELLED);
    RunFixtures.awaitRunEnd(id);
    assertThat(
            RunFixtures.stageRows(
                    RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()), id)
                .get(STRUCTURAL_GRAPH.name())
                .get("status"))
        .isEqualTo("CANCELLED");
  }

  @Test
  void aFalhaDoPrincipalCancelaOIrmao() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final ProtocolError erroDoPrincipal = ErrorCodes.tooManyJavaSources(1, 2);
    final CountDownLatch irmaoRodando = new CountDownLatch(1);
    final AnalysisRegistry registro =
        registro(
            RunFixtures.lightPlan()
                .with(
                    GIT_HISTORY_CHEAP,
                    RunFixtures.pollingUntilCancelled(irmaoRodando, UNCANCELLED_LIMIT))
                .with(
                    SOURCE_ANALYSIS,
                    context -> {
                      awaitLatch(irmaoRodando, AWAIT);
                      throw new StageFailedException(erroDoPrincipal);
                    }));

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);

    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.failed");
    assertThat(terminal.payload().path("error").path("code").asText())
        .as("o erro da análise é o original, do principal")
        .isEqualTo(erroDoPrincipal.code());
    assertThat(desfechoDoEstagio(id, SOURCE_ANALYSIS)).isEqualTo("FAILED");
    assertThat(desfechoDoEstagio(id, GIT_HISTORY_CHEAP))
        .as("a falha do principal cancelou o irmão")
        .isEqualTo("CANCELLED");
  }

  private static boolean dentroDoEndDoVisitante() {
    return StackWalker.getInstance()
        .walk(
            pilha ->
                pilha.anyMatch(
                    quadro ->
                        quadro.getClassName().equals(SourceProgressVisitor.class.getName())
                            && "end".equals(quadro.getMethodName())));
  }

  @Test
  void umCancelamentoQueChegaDepoisDaUltimaGravacaoAindaCancelaOSourceAnalysis() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("fonte");
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(repositorio, "src/main/java/a/A.java", "package a;\nclass A {}\n");
      TestRepositories.write(
          repositorio, "src/main/java/a/Quebrado.java", "package a;\nclass Quebrado {\n");
      TestRepositories.commitAll(git, "fonte\n");
    }
    final AnalysisRegistry registro = registro(Stages.production());
    final AtomicBoolean cancelou = new AtomicBoolean();
    quadros.observe(
        quadro -> {
          final boolean passoDoFim =
              "analysis.progress".equals(quadro.event())
                  && SOURCE_ANALYSIS.name().equals(quadro.stage())
                  && dentroDoEndDoVisitante();
          if (passoDoFim && cancelou.compareAndSet(false, true)) {
            final AnalysisRegistry.CancelResult pedido = registro.cancel(RunFixtures.FIXTURE_ID);
            assertThat(pedido.cancelled()).isTrue();
            pedido.afterResponse().run();
          }
        });

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(id).isEqualTo(RunFixtures.FIXTURE_ID);
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);

    assertThat(cancelou).as("anti-vacuidade: o passo do end saiu e o cancelamento veio").isTrue();
    assertThat(terminal.event()).isEqualTo("analysis.cancelled");
    final Frame fonte =
        quadros.events(id, "analysis.stage.completed").stream()
            .filter(quadro -> SOURCE_ANALYSIS.name().equals(quadro.stage()))
            .findFirst()
            .orElseThrow();
    assertThat(fonte.payload().path("status").asText())
        .as("o token é consultado logo depois do analyze: o estágio é CANCELLED")
        .isEqualTo("CANCELLED");
    assertThat(fonte.payload().path("counters").size())
        .as("nenhum contador de um estágio cancelado")
        .isZero();
    assertThat(quadros.events(id, "analysis.warning"))
        .as("nenhum aviso de um estágio cancelado, e havia um arquivo com erro de sintaxe")
        .isEmpty();
  }
}
