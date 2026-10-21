package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class RunEndingTest {

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

  private AnalysisRegistry registro(FrameSink sink, StagePlan plano, LongSupplier nanos)
      throws IOException {
    final AnalysisRegistry registro =
        new AnalysisRegistry(RunFixtures.settings(dados, sink, plano, nanos));
    registros.add(registro);
    return registro;
  }

  private CountDownLatch trinco() {
    final CountDownLatch trinco = new CountDownLatch(1);
    trincos.add(trinco);
    return trinco;
  }

  private static final class ErroNoFio extends Error {
    private static final long serialVersionUID = 1L;

    ErroNoFio() {
      super("erro sintético ao escrever um quadro");
    }
  }

  private static StagePlan corrompeUmaVez(AtomicReference<Throwable> noTratador) {
    final AtomicBoolean jaCorrompeu = new AtomicBoolean();
    return RunFixtures.lightPlan()
        .with(
            STRUCTURAL_GRAPH,
            context -> {
              if (!jaCorrompeu.compareAndSet(false, true)) {
                return StageOutcome.skipped();
              }
              guardarOQueChegaAoTratador(noTratador);
              throw new SQLiteException("corrupção sintética", SQLiteErrorCode.SQLITE_CORRUPT);
            });
  }

  private static void guardarOQueChegaAoTratador(AtomicReference<Throwable> noTratador) {
    final Thread atual = Thread.currentThread();
    final Thread.UncaughtExceptionHandler original = atual.getUncaughtExceptionHandler();
    atual.setUncaughtExceptionHandler(
        (thread, erro) -> {
          noTratador.set(erro);
          original.uncaughtException(thread, erro);
        });
  }

  private FrameSink quebraNoInvalidated(boolean dali) {
    final AtomicBoolean quebrou = new AtomicBoolean();
    return envelope -> {
      if (dali && quebrou.get()) {
        throw new ErroNoFio();
      }
      quadros.emit(envelope);
      final boolean invalidated = "cache.invalidated".equals(quadros.frames().getLast().event());
      if (invalidated && quebrou.compareAndSet(false, true)) {
        throw new ErroNoFio();
      }
    };
  }

  @Test
  void umErrorNoPassoTerminalAindaEmiteOTerminalELiberaAVaga() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AtomicReference<Throwable> noTratador = new AtomicReference<>();
    final AnalysisRegistry registro =
        registro(quebraNoInvalidated(false), corrompeUmaVez(noTratador), ManualNanoClock.frozen());

    final AnalysisId id = launch(registro, repositorio, defaults());
    RunFixtures.awaitRunEnd(id);

    final List<Frame> daAnalise = quadros.of(id);
    final Frame ultimo = daAnalise.getLast();
    assertThat(ultimo.isTerminal())
        .as("o último quadro da análise é um evento terminal; chegaram %s", daAnalise)
        .isTrue();
    final ProtocolError esperado = ErrorCodes.internalUnexpected(ErroNoFio.class.getName());
    assertThat(ultimo.event()).as(ultimo.line()).isEqualTo("analysis.failed");
    assertThat(ultimo.payload().path("error").path("code").asText()).isEqualTo(esperado.code());
    assertThat(ultimo.payload().path("error").path("detail").asText())
        .as("o nome da classe do Error que escapou, nunca a mensagem")
        .isEqualTo(esperado.detail());
    assertThat(daAnalise.stream().filter(Frame::isTerminal)).as("um único terminal").hasSize(1);

    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(retrato.failure()).isEqualTo(esperado);
    assertThat(noTratador.get())
        .as("o Error segue até o tratador da thread: a produção não o engole")
        .isInstanceOf(ErroNoFio.class);

    final AnalysisId depois = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(depois, AWAIT).event())
        .as("a vaga foi liberada, e o cache reconstruído serve a próxima análise")
        .isEqualTo("analysis.completed");
  }

  @Test
  void quandoNemOTerminalPodeSerEscritoORetratoTerminaEAVagaFicaLivre() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AtomicReference<Throwable> noTratador = new AtomicReference<>();
    final AnalysisRegistry registro =
        registro(quebraNoInvalidated(true), corrompeUmaVez(noTratador), ManualNanoClock.frozen());

    final AnalysisId id = launch(registro, repositorio, defaults());
    RunFixtures.awaitRunEnd(id);

    assertThat(quadros.of(id).stream().filter(Frame::isTerminal))
        .as("o fio recusou todo quadro depois do cache.invalidated")
        .isEmpty();
    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status())
        .as("analysis.status nunca diz RUNNING de uma análise que acabou")
        .isEqualTo(AnalysisStatus.FAILED);
    assertThat(retrato.failure())
        .isEqualTo(ErrorCodes.internalUnexpected(ErroNoFio.class.getName()));
    assertThat(registro.start(repositorio.toString(), repositorio, defaults()))
        .as("a vaga foi liberada: a sessão não fica presa em ANALYSIS_ALREADY_RUNNING")
        .isInstanceOf(StartResult.Started.class);
    assertThat(noTratador.get()).isInstanceOf(ErroNoFio.class);
    assertThat(noTratador.get().getSuppressed())
        .as("o Error da escrita do terminal vai suprimido no original")
        .isNotEmpty()
        .allMatch(ErroNoFio.class::isInstance);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(classes = {OutOfMemoryError.class, NoClassDefFoundError.class})
  void umErrorDepoisDeUmCancelamentoAceitoAindaTerminaCancelado(Class<? extends Error> tipo)
      throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final Error erro = tipo.getConstructor(String.class).newInstance("erro sintético do teste");
    final AtomicReference<Throwable> noTratador = new AtomicReference<>();
    final CountDownLatch dentro = new CountDownLatch(1);
    final AnalysisRegistry registro =
        registro(
            quadros,
            RunFixtures.lightPlan()
                .with(
                    STRUCTURAL_GRAPH,
                    context -> {
                      guardarOQueChegaAoTratador(noTratador);
                      dentro.countDown();
                      final long prazo = System.nanoTime() + AWAIT.toNanos();
                      while (!context.cancellation().isCancelled() && System.nanoTime() < prazo) {
                        awaitLatch(new CountDownLatch(1), Duration.ofMillis(5));
                      }
                      throw erro;
                    }),
            ManualNanoClock.frozen());

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).isTrue();
    final AnalysisRegistry.CancelResult pedido = registro.cancel(id);
    assertThat(pedido.cancelled()).isTrue();
    pedido.afterResponse().run();

    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    assertThat(terminal.event())
        .as("{cancelled: true} implica analysis.cancelled, mesmo contra um Error")
        .isEqualTo("analysis.cancelled");
    RunFixtures.awaitRunEnd(id);
    assertThat(registro.status(id).orElseThrow().status()).isEqualTo(AnalysisStatus.CANCELLED);
    assertThat(
            quadros.events(id, "analysis.stage.completed").stream()
                .filter(quadro -> STRUCTURAL_GRAPH.name().equals(quadro.stage()))
                .map(quadro -> quadro.payload().path("status").asText()))
        .as("o estágio que rodava termina CANCELLED, com o seu evento")
        .containsExactly("CANCELLED");
    final Path banco = RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit());
    assertThat(RunFixtures.analysisRow(banco, id).orElseThrow().get("status"))
        .isEqualTo("CANCELLED");
    assertThat(RunFixtures.stageRows(banco, id).get(STRUCTURAL_GRAPH.name()).get("status"))
        .isEqualTo("CANCELLED");
    assertThat(noTratador.get())
        .as("o Error não é classificado: segue até o tratador da thread")
        .isSameAs(erro);
  }

  @Test
  void umComecoPedidoNoEventoTerminalEAceito() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AtomicReference<AnalysisRegistry> doTeste = new AtomicReference<>();
    final AtomicReference<StartResult> pedidoNoTerminal = new AtomicReference<>();
    quadros.observe(
        quadro -> {
          if (quadro.isOf(RunFixtures.FIXTURE_ID) && quadro.isTerminal()) {
            pedidoNoTerminal.set(
                doTeste.get().start(repositorio.toString(), repositorio, defaults()));
          }
        });
    doTeste.set(registro(quadros, RunFixtures.lightPlan(), ManualNanoClock.frozen()));

    final AnalysisId primeira = launch(doTeste.get(), repositorio, defaults());
    assertThat(primeira).isEqualTo(RunFixtures.FIXTURE_ID);
    assertThat(quadros.awaitTerminal(primeira, AWAIT).event()).isEqualTo("analysis.completed");
    RunFixtures.awaitRunEnd(primeira);

    assertThat(pedidoNoTerminal.get())
        .as("o começo pedido dentro do emit do terminal encontrou a vaga livre")
        .isInstanceOf(StartResult.Started.class);
    final StartResult.Started segunda = (StartResult.Started) pedidoNoTerminal.get();
    segunda.launch().run();
    assertThat(quadros.awaitTerminal(segunda.analysisId(), AWAIT).event())
        .isEqualTo("analysis.completed");
  }

  @Test
  void oStdoutQuebradoCancelaAPropriaAnalise() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AtomicBoolean quebrado = new AtomicBoolean();
    final AtomicInteger tentadosDepois = new AtomicInteger();
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch nunca = trinco();
    final AnalysisRegistry registro =
        registro(
            envelope -> {
              if (quebrado.get()) {
                tentadosDepois.incrementAndGet();
                throw new IOException("Broken pipe (teste)");
              }
              quadros.emit(envelope);
            },
            RunFixtures.lightPlan()
                .with(
                    STRUCTURAL_GRAPH,
                    context -> {
                      dentro.countDown();
                      final long prazo = System.nanoTime() + UNCANCELLED_LIMIT.toNanos();
                      long feitos = 0;
                      while (System.nanoTime() < prazo) {
                        context.cancellation().throwIfCancelled();
                        context.progress().report(ProgressUnit.TYPES, feitos++, null);
                        awaitLatch(nunca, Duration.ofMillis(5));
                      }
                      return StageOutcome.completed(StageCounters.none());
                    }),
            ManualNanoClock.stepping(Duration.ofMillis(200)));

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).isTrue();
    quebrado.set(true);
    RunFixtures.awaitRunEnd(id);

    assertThat(registro.status(id).orElseThrow().status())
        .as("o stdout quebrado cancela a análise, que não roda até o fim às cegas")
        .isEqualTo(AnalysisStatus.CANCELLED);
    assertThat(registro.status(id).orElseThrow().stages().get(STRUCTURAL_GRAPH).status())
        .isEqualTo(StageStatus.CANCELLED);
    final Path banco = RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit());
    assertThat(RunFixtures.analysisRow(banco, id).orElseThrow().get("status"))
        .isEqualTo("CANCELLED");
    assertThat(tentadosDepois)
        .as("a falha é vista uma só vez, e nada mais é tentado no fio quebrado")
        .hasValue(1);
    assertThat(registro.start(repositorio.toString(), repositorio, defaults()))
        .isInstanceOf(StartResult.Started.class);
  }
}
