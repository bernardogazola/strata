package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitDeath;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.persistence.ElementWriter;
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchVisitor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ModeledBatch;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class CancellationTest {

  static final int HEAVY_FILES = 280;
  static final int HEAVY_METHODS = 40;
  private static final int LIGHT_ROOTS = 3;
  private static final int LIGHT_FILES = 15;
  private static final int LIGHT_METHODS = 4;
  private static final int FILLER_PER_ROOT = 1_500;

  private static final Duration GATE = Duration.ofMillis(1_000);

  private static final Duration DEADLINE = Duration.ofMillis(2_000);

  private static final String ANALYSIS_PACKAGE = "br.pucpr.tcc.strata.analyzer.";

  @TempDir static Path gerados;

  private static Path repositorio;
  private static String commit;

  @TempDir Path dados;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private final ExecutorService leitor =
      Executors.newSingleThreadExecutor(
          tarefa -> {
            final Thread thread = new Thread(tarefa, "cancellation-test-reader");
            thread.setDaemon(true);
            return thread;
          });
  private AnalysisRegistry registro;
  private final List<CountDownLatch> trincos = new ArrayList<>();

  @BeforeAll
  static void gerarORepositorio() throws Exception {
    HermeticSystemReader.install();
    repositorio = gerados.toRealPath().resolve("gerado");
    for (int raiz = 0; raiz <= LIGHT_ROOTS; raiz++) {
      final boolean pesada = raiz == 0;
      final int arquivos = pesada ? HEAVY_FILES : LIGHT_FILES;
      final int metodos = pesada ? HEAVY_METHODS : LIGHT_METHODS;
      for (int arquivo = 0; arquivo < arquivos; arquivo++) {
        TestRepositories.write(
            repositorio,
            "mod-0" + raiz + "/src/main/java/gen/m" + raiz + "/C" + arquivo + ".java",
            classe(raiz, arquivo, metodos));
      }
      for (int texto = 0; texto < FILLER_PER_ROOT; texto++) {
        TestRepositories.write(repositorio, "mod-0" + raiz + "/res/r" + texto + ".txt", "r\n");
      }
    }
    try (var git = TestRepositories.init(repositorio)) {
      commit = TestRepositories.commitAll(git, "gerado\n").name();
    }
  }

  private static String classe(int raiz, int arquivo, int metodos) {
    final String anterior = "C" + Math.max(0, arquivo - 1);
    final StringBuilder fonte =
        new StringBuilder("package gen.m" + raiz + ";\n\npublic class C" + arquivo + " {\n");
    for (int i = 0; i < metodos; i++) {
      fonte
          .append("  public int m")
          .append(i)
          .append('(')
          .append(anterior)
          .append(" other, int x) {\n    return x > ")
          .append(i)
          .append(" ? other.m")
          .append(i)
          .append("(other, x - 1) : x;\n  }\n");
    }
    return fonte.append("}\n").toString();
  }

  @AfterEach
  void fechar() {
    trincos.forEach(CountDownLatch::countDown);
    leitor.shutdownNow();
    if (registro != null) {
      registro.close();
    }
  }

  private AnalysisRegistry registro(StagePlan plano) throws IOException {
    registro =
        new AnalysisRegistry(
            RunFixtures.settings(
                dados, quadros, plano, ManualNanoClock.stepping(Duration.ofMillis(150))));
    return registro;
  }

  private boolean cancelarComoOLeitor(AnalysisId id) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              final AnalysisRegistry.CancelResult resultado = registro.cancel(id);
              resultado.afterResponse().run();
              return resultado.cancelled();
            },
            leitor)
        .get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  enum Site {
    WALK,
    PRE_PARSE,
    BUILD,
    UNIT,
    FLUSH,
    END,
    SIBLING
  }

  private enum Fase {
    PRE_PARSE,
    BETWEEN_BATCHES,
    VISIT,
    END
  }

  private final class Sonda {
    private final Site site;
    private final AtomicBoolean disparou = new AtomicBoolean();
    private final AtomicLong disparoNanos = new AtomicLong();
    private final AtomicInteger lotes = new AtomicInteger();
    private volatile Fase fase = Fase.PRE_PARSE;
    private volatile AnalysisId analise;
    private volatile boolean desarmada;

    private final CountDownLatch fonteComecou = new CountDownLatch(1);

    Sonda(Site site) {
      this.site = site;
    }

    void consulta() {
      if (desarmada || disparou.get() || !naFase()) {
        return;
      }
      if (naPilha(site) && disparou.compareAndSet(false, true)) {
        disparar();
      }
    }

    void quadro(Frame quadro) {
      final boolean daCaminhada =
          site == Site.WALK
              && "analysis.progress".equals(quadro.event())
              && AnalysisStage.REPOSITORY_VALIDATION.name().equals(quadro.stage());
      if (!desarmada && daCaminhada && naPilha(Site.WALK) && disparou.compareAndSet(false, true)) {
        disparar();
      }
    }

    private boolean naFase() {
      return switch (site) {
        case PRE_PARSE -> fase == Fase.PRE_PARSE;
        case BUILD -> fase == Fase.BETWEEN_BATCHES && lotes.get() >= 1;
        case UNIT -> fase == Fase.VISIT && lotes.get() >= 1;
        case FLUSH -> fase == Fase.VISIT;
        case END -> fase == Fase.END;
        case WALK, SIBLING -> false;
      };
    }

    private void disparar() {
      try {
        assertThat(cancelarComoOLeitor(analise))
            .as("o cancelamento de uma análise RUNNING")
            .isTrue();
      } catch (Exception falha) {
        throw new IllegalStateException("o cancelamento da sonda falhou", falha);
      }
      disparoNanos.set(System.nanoTime());
    }

    boolean disparou() {
      return disparou.get();
    }
  }

  static boolean naPilha(Site site) {
    final List<String> quadros =
        StackWalker.getInstance()
            .walk(s -> s.map(f -> f.getClassName() + "#" + f.getMethodName()).toList());
    final Predicate<String> ha = alvo -> quadros.stream().anyMatch(q -> q.matches(alvo));
    return switch (site) {
      case WALK -> ha.test(".*\\.repository\\.RepositoryWalker(\\$.*)?#.*");
      case PRE_PARSE -> ha.test(".*\\.sourceanalysis\\.SourceAnalysisRun#preParse.*");
      case BUILD -> ha.test(".*\\.CancellationListener(\\$.*)?#.*");
      case UNIT -> ha.test(".*\\.sourceanalysis\\.ModeledBatch#perCompilationUnit");
      case FLUSH ->
          ha.test(".*\\.persistence\\.dao\\.MultiRowInsert#.*")
              && ha.test(".*\\.persistence\\.ElementWriter#accept")
              && !ha.test(".*\\.persistence\\.ElementWriter#end");
      case END -> ha.test(".*\\.persistence\\.ElementWriter#end");
      case SIBLING -> false;
    };
  }

  private record VisitanteMarcado(BatchVisitor delegado, Sonda sonda) implements BatchVisitor {

    @Override
    public void begin(List<SourceFile> files) {
      delegado.begin(files);
      sonda.fase = Fase.BETWEEN_BATCHES;
    }

    @Override
    public void visit(ModeledBatch batch) {
      sonda.fase = Fase.VISIT;
      delegado.visit(batch);
      sonda.lotes.incrementAndGet();
      sonda.fase = Fase.BETWEEN_BATCHES;
    }

    @Override
    public void end(SourceAnalysis analysis) {
      sonda.fase = Fase.END;
      delegado.end(analysis);
    }
  }

  private static final class ProbedSourceAnalysis implements StageExecutor {
    private final Sonda sonda;
    private final AtomicReference<SqliteConnections> conexoes = new AtomicReference<>();
    private final AtomicReference<Throwable> escapou = new AtomicReference<>();

    ProbedSourceAnalysis(Sonda sonda) {
      this.sonda = sonda;
    }

    @Override
    public StageOutcome execute(StageContext context) throws SQLException {
      conexoes.set(context.connections());
      sonda.fonteComecou.countDown();
      final BooleanSupplier cancelado =
          () -> {
            sonda.consulta();
            return context.cancellation().isCancelled();
          };
      final List<SourceRoot> raizes = context.inputs().selectedRoots();
      final ElementWriter escritor =
          new ElementWriter(
              context.connections(),
              context.analysisPk(),
              raizes,
              context.inputs().repository().snapshot().inventory().javaSources(),
              cancelado);
      final SourceAnalysis analise;
      try {
        analise =
            new SpoonModelBuilder()
                .analyze(
                    context.inputs().repository(),
                    raizes,
                    new VisitanteMarcado(new StructureExtractor(raizes, escritor), sonda),
                    cancelado);
      } catch (RuntimeException | Error falha) {
        escapou.set(falha);
        throw falha;
      }
      context
          .completeness()
          .recordSourceFiles(analise.totalFiles(), analise.skippedFiles(), analise.parseFailures());
      return StageOutcome.completed(StageCounters.none());
    }
  }

  private static final class IrmaoQueConsulta implements StageExecutor {
    private final Sonda sonda;
    private final AtomicLong saiuNanos = new AtomicLong();

    IrmaoQueConsulta(Sonda sonda) {
      this.sonda = sonda;
    }

    @Override
    public StageOutcome execute(StageContext context) {
      try {
        final CountDownLatch nunca = new CountDownLatch(1);
        final long prazo = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < prazo) {
          if (sonda.site == Site.SIBLING
              && !sonda.desarmada
              && awaitLatch(sonda.fonteComecou, AWAIT)
              && sonda.disparou.compareAndSet(false, true)) {
            sonda.disparar();
          }
          context.cancellation().throwIfCancelled();
          if (sonda.site != Site.SIBLING || sonda.desarmada) {
            return StageOutcome.skipped();
          }
          awaitLatch(nunca, Duration.ofMillis(10));
        }
        return StageOutcome.skipped();
      } finally {
        saiuNanos.set(System.nanoTime());
      }
    }
  }

  @ParameterizedTest(name = "virada em {0}")
  @EnumSource(Site.class)
  void cancelarNumPontoDeVerificacaoDeixaAAnaliseOciosaECanceladaDentroDoPortao(Site site)
      throws Exception {
    final Sonda sonda = new Sonda(site);
    final ProbedSourceAnalysis fonte = new ProbedSourceAnalysis(sonda);
    final IrmaoQueConsulta irmao = new IrmaoQueConsulta(sonda);
    registro(
        Stages.production()
            .with(AnalysisStage.SOURCE_ANALYSIS, fonte)
            .with(AnalysisStage.GIT_HISTORY_CHEAP, irmao));
    quadros.observe(sonda::quadro);

    final StartResult.Started partida =
        (StartResult.Started) registro.start(repositorio.toString(), repositorio, defaults());
    sonda.analise = partida.analysisId();
    final AnalysisId id = partida.analysisId();
    partida.launch().run();
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);

    assertThat(sonda.disparou())
        .as(
            site
                + ": o ponto de verificação nunca consultou o cancelamento; falta o ponto, e a"
                + " prova seria vacuidade")
        .isTrue();
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.cancelled");
    assertThat(Duration.ofNanos(terminal.nanos() - sonda.disparoNanos.get()))
        .as(site + ": da virada ao analysis.cancelled em até 1 s")
        .isLessThanOrEqualTo(GATE);
    final List<Frame> daAnalise = quadros.of(id);
    assertThat(daAnalise.get(daAnalise.size() - 1)).isEqualTo(terminal);
    assertThat(quadros.events(id, "analysis.cancelled")).hasSize(1);

    final Thread daAnaliseThread = RunFixtures.runThread(quadros, id);
    awaitDeath(daAnaliseThread);
    if (site == Site.SIBLING) {
      assertThat(irmao.saiuNanos.get())
          .as("o irmão saiu antes do evento terminal: nenhuma tarefa do pool sobra")
          .isPositive()
          .isLessThan(terminal.nanos());
    }
    quadros
        .events(id, "analysis.stage.completed")
        .forEach(q -> assertThat(q.index()).isLessThan(terminal.index()));

    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status())
        .as("um cancelado é reportado CANCELLED")
        .isEqualTo(AnalysisStatus.CANCELLED);
    assertThat(registro.readable(id)).as("os dados de um cancelado nunca são servidos").isEmpty();

    final Path banco = RunFixtures.database(dados, repositorio, commit);
    if (site == Site.WALK) {
      assertThat(retrato.stages().get(AnalysisStage.REPOSITORY_VALIDATION).status())
          .isEqualTo(StageStatus.CANCELLED);
      assertThat(RunFixtures.analysisRow(banco, id))
          .as("cancelada antes da ligação ao cache: nenhuma linha")
          .isEmpty();
    } else {
      verificarLinhaCancelada(id, banco, fonte, retrato, terminal, site);
    }

    sonda.desarmada = true;
    final AnalysisId segunda = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(segunda, AWAIT).event())
        .as("um segundo começo é aceito e completa")
        .isEqualTo("analysis.completed");
    assertThat(registro.readable(segunda)).as("o controle positivo de readable").isPresent();
    assertThat(registro.readable(id)).isEmpty();
    if (site != Site.WALK) {
      verificarLotesInteiros(banco, id, segunda);
    }
  }

  private void verificarLinhaCancelada(
      AnalysisId id,
      Path banco,
      ProbedSourceAnalysis fonte,
      RunSnapshot retrato,
      Frame terminal,
      Site site)
      throws Exception {
    assertThat(fonte.escapou.get())
        .as("o analyze termina com CancellationException, nunca WriteFailedException")
        .isInstanceOf(CancellationException.class);
    final SqliteConnections conexoes = fonte.conexoes.get();
    assertThat(conexoes).as("o SOURCE_ANALYSIS rodou depois da ligação").isNotNull();
    assertThat(
            CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return conexoes.write(c -> "livre");
                      } catch (SQLException falha) {
                        throw new IllegalStateException(falha);
                      }
                    })
                .get(1, TimeUnit.SECONDS))
        .as("o escritor está livre na hora")
        .isEqualTo("livre");

    final Map<String, Object> analise = RunFixtures.analysisRow(banco, id).orElseThrow();
    assertThat(analise.get("status")).as("a linha é CANCELLED").isEqualTo("CANCELLED");
    assertThat(analise.get("finished_at")).isNotNull();
    assertThat(analise.get("failure_code")).isNull();
    assertThat(RunFixtures.fileElements(banco, id)).as("o end() nunca confirmou: 0 FILE").isZero();
    assertThat(RunFixtures.orphanDependencies(banco, id)).isZero();

    assertThat(retrato.stages().get(AnalysisStage.SOURCE_ANALYSIS).status())
        .isEqualTo(StageStatus.CANCELLED);
    assertThat(
            quadros.events(id, "analysis.stage.completed").stream()
                .filter(q -> AnalysisStage.SOURCE_ANALYSIS.name().equals(q.stage()))
                .map(q -> q.payload().path("status").asText()))
        .containsExactly("CANCELLED");
    for (final AnalysisStage depois :
        List.of(
            AnalysisStage.STRUCTURAL_GRAPH,
            AnalysisStage.GIT_HISTORY_DETAILED,
            AnalysisStage.EVOLUTIONARY_COUPLING,
            AnalysisStage.HOTSPOT_PROFILES,
            AnalysisStage.GUIDED_TOUR,
            AnalysisStage.PERSISTENCE)) {
      assertThat(retrato.stages().get(depois).status())
          .as(depois.name())
          .isEqualTo(StageStatus.PENDING);
      assertThat(RunFixtures.stageRows(banco, id).get(depois.name()).get("status"))
          .isEqualTo("PENDING");
    }
    final long selecionados = HEAVY_FILES + (long) LIGHT_ROOTS * LIGHT_FILES;
    assertThat(numero(analise.get("total_files"))).isEqualTo(selecionados);
    assertThat(numero(analise.get("skipped_files"))).isZero();
    final JsonNode completude = terminal.payload().path("completeness");
    assertThat(completude.path("totalFiles").asLong())
        .as("a completude do cancelamento é a última gravada: a da ligação, (total, 0, 0)")
        .isEqualTo(selecionados);
    assertThat(completude.path("skippedFiles").asLong()).isZero();
    assertThat(completude.path("parseFailures").asLong()).isZero();
  }

  private static long numero(Object valor) {
    return ((Number) valor).longValue();
  }

  private static void verificarLotesInteiros(Path banco, AnalysisId cancelada, AnalysisId completa)
      throws Exception {
    final Map<String, TreeSet<String>> parcial = RunFixtures.elementsByFile(banco, cancelada);
    final Map<String, TreeSet<String>> inteiro = RunFixtures.elementsByFile(banco, completa);
    assertThat(inteiro).as("a análise completa tem elementos").isNotEmpty();
    for (final Map.Entry<String, TreeSet<String>> arquivo : parcial.entrySet()) {
      assertThat(arquivo.getValue())
          .as(arquivo.getKey() + ": um arquivo pela metade")
          .isEqualTo(inteiro.get(arquivo.getKey()));
    }
    final Set<String> raizesTocadas = new TreeSet<>();
    parcial.keySet().forEach(caminho -> raizesTocadas.add(caminho.substring(0, 6)));
    for (final String raiz : raizesTocadas) {
      assertThat(parcial.keySet().stream().filter(c -> c.startsWith(raiz)).toList())
          .as(raiz + ": um lote pela metade")
          .containsExactlyInAnyOrderElementsOf(
              inteiro.keySet().stream().filter(c -> c.startsWith(raiz)).toList());
    }
  }

  @Test
  void cancelarNoPrimeiroPassoDoSourceAnalysisChegaAoCanceladoDentroDoPrazo() throws Exception {
    registro(Stages.production());
    final AtomicReference<AnalysisId> analise = new AtomicReference<>();
    final AtomicLong viradaNanos = new AtomicLong();
    final AtomicBoolean virou = new AtomicBoolean();
    quadros.observe(
        quadro -> {
          final boolean primeiroComAvanco =
              "analysis.progress".equals(quadro.event())
                  && AnalysisStage.SOURCE_ANALYSIS.name().equals(quadro.stage())
                  && quadro.payload().path("completed").asLong() > 0;
          if (primeiroComAvanco && virou.compareAndSet(false, true)) {
            try {
              assertThat(cancelarComoOLeitor(analise.get())).isTrue();
            } catch (Exception falha) {
              throw new IllegalStateException(falha);
            }
            viradaNanos.set(System.nanoTime());
          }
        });

    final StartResult.Started partida =
        (StartResult.Started) registro.start(repositorio.toString(), repositorio, defaults());
    analise.set(partida.analysisId());
    partida.launch().run();
    final Frame terminal = quadros.awaitTerminal(partida.analysisId(), AWAIT);

    assertThat(virou).as("anti-vacuidade: houve um passo com completed > 0").isTrue();
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.cancelled");
    assertThat(Duration.ofNanos(terminal.nanos() - viradaNanos.get()))
        .isLessThanOrEqualTo(DEADLINE);
  }

  @Test
  void cancelarUmIdDesconhecidoOuTerminadoResponderFalsoENuncaErro() throws Exception {
    registro(RunFixtures.lightPlan());
    assertThat(registro.cancel(RunFixtures.idAt(500)).cancelled()).isFalse();

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(id, AWAIT).event()).isEqualTo("analysis.completed");

    final AnalysisRegistry.CancelResult terminada = registro.cancel(id);
    assertThat(terminada.cancelled()).isFalse();
    terminada.afterResponse().run();
    assertThat(registro.status(id).orElseThrow().status()).isEqualTo(AnalysisStatus.COMPLETED);
  }

  @Test
  void cancelarUmaAnaliseFinalizandoResponderFalsoEElaCompleta() throws Exception {
    registro(RunFixtures.lightPlan());
    final CountDownLatch segurada = new CountDownLatch(1);
    final CountDownLatch soltar = new CountDownLatch(1);
    trincos.add(soltar);
    quadros.observe(
        quadro -> {
          if ("analysis.stage.completed".equals(quadro.event())
              && AnalysisStage.PERSISTENCE.name().equals(quadro.stage())) {
            segurada.countDown();
            awaitLatch(soltar, AWAIT);
          }
        });

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(segurada, AWAIT)).isTrue();

    final AnalysisRegistry.CancelResult finalizando = registro.cancel(id);
    assertThat(finalizando.cancelled()).as("FINALIZING → {cancelled: false}").isFalse();
    finalizando.afterResponse().run();
    soltar.countDown();

    assertThat(quadros.awaitTerminal(id, AWAIT).event()).isEqualTo("analysis.completed");
  }

  @Test
  void cancelarDuasVezesRespondeVerdadeiroDuasVezesEUmSoEventoTerminal() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    registro(
        RunFixtures.lightPlan()
            .with(
                AnalysisStage.STRUCTURAL_GRAPH, RunFixtures.pollingUntilCancelled(dentro, AWAIT)));
    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).isTrue();

    final AnalysisRegistry.CancelResult primeiro = registro.cancel(id);
    final AnalysisRegistry.CancelResult segundo = registro.cancel(id);
    assertThat(primeiro.cancelled()).isTrue();
    assertThat(segundo.cancelled()).isTrue();
    primeiro.afterResponse().run();
    segundo.afterResponse().run();

    assertThat(quadros.awaitTerminal(id, AWAIT).event()).isEqualTo("analysis.cancelled");
    awaitDeath(RunFixtures.runThread(quadros, id));
    assertThat(quadros.of(id).stream().filter(Frame::isTerminal)).hasSize(1);
  }

  @Test
  void umPedidoDeCancelamentoSemOGanchoAindaTerminaCancelado() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch soltar = new CountDownLatch(1);
    trincos.add(soltar);
    registro(
        RunFixtures.lightPlan()
            .with(
                AnalysisStage.PERSISTENCE,
                context -> {
                  dentro.countDown();
                  awaitLatch(soltar, AWAIT);
                  return StageOutcome.completed(StageCounters.none());
                }));
    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).isTrue();

    final AnalysisRegistry.CancelResult pedido = registro.cancel(id);
    assertThat(pedido.cancelled()).isTrue();
    soltar.countDown();

    assertThat(quadros.awaitTerminal(id, AWAIT).event())
        .as("{cancelled: true} implica analysis.cancelled, mesmo sem o gancho")
        .isEqualTo("analysis.cancelled");
    assertThat(registro.status(id).orElseThrow().status()).isEqualTo(AnalysisStatus.CANCELLED);
  }
}
