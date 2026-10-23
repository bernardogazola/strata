package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphAnalysis;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphInputDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class StructuralGraphStageCancellationTest {

  static final int TYPES = 70;
  private static final int HALF = TYPES / 2;

  private static final int PACKAGES = 2;

  private static final int PACKAGE_EDGES = 2;

  @TempDir static Path gerados;

  private static Path repositorio;
  private static String commit;

  private static List<Fronteira> ensaio;

  private static String terminalDoEnsaio;
  private static long linhasDoEnsaio;

  @TempDir Path dados;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private final ExecutorService leitor =
      Executors.newSingleThreadExecutor(
          tarefa -> {
            final Thread thread = new Thread(tarefa, "structural-graph-cancel-reader");
            thread.setDaemon(true);
            return thread;
          });
  private AnalysisRegistry registro;

  enum Fronteira {
    LEITURA,
    GRAFO,
    METRICAS,
    ARESTAS,
    OUTRA;

    static Optional<Fronteira> daClasse(String nome) {
      if (nome.equals(GraphInputDao.class.getName())) {
        return Optional.of(LEITURA);
      }
      if (nome.startsWith(StructuralGraphAnalysis.class.getPackageName() + ".")) {
        return Optional.of(GRAFO);
      }
      if (nome.equals(GraphMetricDao.class.getName())) {
        return Optional.of(METRICAS);
      }
      if (nome.equals(PackageEdgeDao.class.getName())) {
        return Optional.of(ARESTAS);
      }
      return Optional.empty();
    }
  }

  enum Virada {
    NO_MEIO_DA_LEITURA(Fronteira.LEITURA, total -> 1, true),
    LOGO_DEPOIS_DA_LEITURA(Fronteira.LEITURA, total -> total - 1, false),
    NO_MEIO_DO_GRAFO(Fronteira.GRAFO, total -> total / 2, true),
    LOGO_DEPOIS_DO_GRAFO(Fronteira.GRAFO, total -> total - 1, false),
    ANTES_DAS_METRICAS(Fronteira.METRICAS, total -> 0, true),
    ANTES_DAS_ARESTAS(Fronteira.ARESTAS, total -> 0, true);

    private final Fronteira fronteira;
    private final IntUnaryOperator qual;
    private final boolean vistaNaConsulta;

    Virada(Fronteira fronteira, IntUnaryOperator qual, boolean vistaNaConsulta) {
      this.fronteira = fronteira;
      this.qual = qual;
      this.vistaNaConsulta = vistaNaConsulta;
    }

    int consulta(List<Fronteira> sequencia) {
      final List<Integer> posicoes = new ArrayList<>();
      for (int i = 0; i < sequencia.size(); i++) {
        if (sequencia.get(i) == fronteira) {
          posicoes.add(i + 1);
        }
      }
      final int escolhida = qual.applyAsInt(posicoes.size());
      assertThat(escolhida)
          .as("o ensaio tem a consulta escolhida de %s: %s", fronteira, sequencia)
          .isBetween(0, posicoes.size() - 1);
      return posicoes.get(escolhida);
    }
  }

  private static final class Sonda implements UnaryOperator<BooleanSupplier> {
    private final int alvo;
    private final boolean vistaNoAlvo;
    private final Runnable cancelar;
    private final List<Fronteira> consultas = new CopyOnWriteArrayList<>();

    Sonda(int alvo, boolean vistaNoAlvo, Runnable cancelar) {
      this.alvo = alvo;
      this.vistaNoAlvo = vistaNoAlvo;
      this.cancelar = cancelar;
    }

    @Override
    public BooleanSupplier apply(BooleanSupplier token) {
      return () -> {
        consultas.add(deQuemConsulta());
        if (consultas.size() == alvo) {
          cancelar.run();
          return vistaNoAlvo && token.getAsBoolean();
        }
        return token.getAsBoolean();
      };
    }

    List<Fronteira> consultas() {
      return List.copyOf(consultas);
    }
  }

  private static Fronteira deQuemConsulta() {
    return StackWalker.getInstance()
        .walk(
            quadros ->
                quadros
                    .map(quadro -> Fronteira.daClasse(quadro.getClassName()))
                    .flatMap(Optional::stream)
                    .findFirst())
        .orElse(Fronteira.OUTRA);
  }

  @BeforeAll
  static void gerarEEnsaiar() throws Exception {
    HermeticSystemReader.install();
    repositorio = gerados.toRealPath().resolve("gerado");
    for (int tipo = 0; tipo < TYPES; tipo++) {
      TestRepositories.write(
          repositorio,
          "src/main/java/" + pacote(tipo).replace('.', '/') + "/T" + tipo + ".java",
          classe(tipo));
    }
    try (var git = TestRepositories.init(repositorio)) {
      commit = TestRepositories.commitAll(git, "gerado\n").name();
    }

    final Path dadosDoEnsaio = Files.createDirectories(gerados.resolve("dados-do-ensaio"));
    final RecordingFrameSink quadrosDoEnsaio = new RecordingFrameSink();
    final Sonda nunca =
        new Sonda(
            0,
            false,
            () -> {
              throw new AssertionError("o ensaio nunca cancela");
            });
    final AnalysisRegistry registroDoEnsaio = registro(dadosDoEnsaio, quadrosDoEnsaio, nunca);
    try {
      final AnalysisId id = RunFixtures.launch(registroDoEnsaio, repositorio, defaults());
      terminalDoEnsaio = quadrosDoEnsaio.awaitTerminal(id, AWAIT).line();
      RunFixtures.awaitRunEnd(id);
      final Path banco = RunFixtures.database(dadosDoEnsaio, repositorio, commit);
      linhasDoEnsaio =
          RunFixtures.count(
              banco, "SELECT count(*) FROM dependency WHERE analysis_pk = ?", chave(banco, id));
    } finally {
      registroDoEnsaio.close();
    }
    ensaio = nunca.consultas();
  }

  private static String pacote(int tipo) {
    return tipo < HALF ? "gen.a" : "gen.b";
  }

  private static String classe(int tipo) {
    final StringBuilder fonte =
        new StringBuilder("package " + pacote(tipo) + ";\n\npublic class T" + tipo + " {\n");
    for (int alvo = 0; alvo < TYPES; alvo++) {
      if (alvo != tipo) {
        final String nome = pacote(alvo) + ".T" + alvo;
        fonte.append("  ").append(nome).append(" f").append(alvo).append(";\n");
        fonte.append("  void m").append(alvo).append('(').append(nome).append(" x) {}\n");
      }
    }
    return fonte.append("}\n").toString();
  }

  private static AnalysisRegistry registro(Path dataDir, RecordingFrameSink sink, Sonda sonda)
      throws IOException {
    final StagePlan plano =
        Stages.production()
            .with(STRUCTURAL_GRAPH, new StructuralGraphStage(ExternalPackages.load(), sonda));
    return new AnalysisRegistry(
        RunFixtures.settings(
            dataDir, sink, plano, ManualNanoClock.stepping(Duration.ofMillis(150))));
  }

  private static long chave(Path banco, AnalysisId id) throws SQLException {
    return ((Number) RunFixtures.analysisRow(banco, id).orElseThrow().get("id")).longValue();
  }

  @AfterEach
  void fechar() {
    leitor.shutdownNow();
    if (registro != null) {
      registro.close();
    }
  }

  private void cancelarComoOLeitor(AnalysisId id) {
    try {
      final boolean cancelou =
          CompletableFuture.supplyAsync(
                  () -> {
                    final AnalysisRegistry.CancelResult pedido = registro.cancel(id);
                    pedido.afterResponse().run();
                    return pedido.cancelled();
                  },
                  leitor)
              .get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(cancelou).as("o cancelamento de uma análise RUNNING").isTrue();
    } catch (Exception falha) {
      throw new IllegalStateException("o cancelamento da sonda falhou", falha);
    }
  }

  @Test
  void oEstagioConsultaNaLeituraNoGrafoENasDuasTabelasNessaOrdem() {
    assertThat(terminalDoEnsaio).contains("\"event\":\"analysis.completed\"");
    assertThat(linhasDoEnsaio)
        .as("a premissa: mais linhas que o passo da leitura")
        .isGreaterThan(AnalysisConstants.GRAPH_READ_CHECK_STRIDE_ROWS);

    assertThat(trechos(ensaio))
        .containsExactly(Fronteira.LEITURA, Fronteira.GRAFO, Fronteira.METRICAS, Fronteira.ARESTAS);
    assertThat(Collections.frequency(ensaio, Fronteira.LEITURA))
        .isEqualTo(
            Math.toIntExact(
                Math.ceilDiv(
                    linhasDoEnsaio, (long) AnalysisConstants.GRAPH_READ_CHECK_STRIDE_ROWS)));
    assertThat(Collections.frequency(ensaio, Fronteira.GRAFO)).isGreaterThan(TYPES);
    assertThat(Collections.frequency(ensaio, Fronteira.METRICAS))
        .isEqualTo(Math.ceilDiv(TYPES + PACKAGES, AnalysisConstants.ROWS_PER_INSERT));
    assertThat(Collections.frequency(ensaio, Fronteira.ARESTAS))
        .isEqualTo(Math.ceilDiv(PACKAGE_EDGES, AnalysisConstants.ROWS_PER_INSERT));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Virada.class)
  void umCancelamentoEmCadaFronteiraParaAliENaoDeixaLinhaDoGrafo(Virada virada) throws Exception {
    final int alvo = virada.consulta(ensaio);
    final Sonda sonda =
        new Sonda(alvo, virada.vistaNaConsulta, () -> cancelarComoOLeitor(RunFixtures.FIXTURE_ID));
    registro = registro(dados, quadros, sonda);

    final AnalysisId id = RunFixtures.launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    RunFixtures.awaitRunEnd(id);

    assertThat(terminal.event()).as("terminal: " + terminal.line()).isEqualTo("analysis.cancelled");
    assertThat(sonda.consultas())
        .as("o estágio parou na consulta %d, e as anteriores são as do ensaio", alvo)
        .containsExactlyElementsOf(ensaio.subList(0, alvo));
    final Frame estagio = concluido(id);
    assertThat(estagio.payload().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(estagio.payload().path("counters").size())
        .as("nenhum contador de um estágio cancelado")
        .isZero();
    assertThat(passosDoGrafo(id))
        .as("o primeiro passo sai depois da consulta do estágio que segue a leitura")
        .hasSize(virada.fronteira == Fronteira.LEITURA ? 0 : 1);

    final Path banco = RunFixtures.database(dados, repositorio, commit);
    final long chave = chave(banco, id);
    assertThat(
            RunFixtures.count(
                banco, "SELECT count(*) FROM graph_metric WHERE analysis_pk = ?", chave))
        .isZero();
    assertThat(
            RunFixtures.count(
                banco, "SELECT count(*) FROM package_edge WHERE analysis_pk = ?", chave))
        .isZero();
  }

  private Frame concluido(AnalysisId id) {
    final List<Frame> concluidos =
        quadros.events(id, "analysis.stage.completed").stream()
            .filter(quadro -> STRUCTURAL_GRAPH.name().equals(quadro.stage()))
            .toList();
    assertThat(concluidos).as("um analysis.stage.completed do STRUCTURAL_GRAPH").hasSize(1);
    return concluidos.get(0);
  }

  private List<Frame> passosDoGrafo(AnalysisId id) {
    return quadros.events(id, "analysis.progress").stream()
        .filter(quadro -> STRUCTURAL_GRAPH.name().equals(quadro.stage()))
        .toList();
  }

  private static List<Fronteira> trechos(List<Fronteira> consultas) {
    final List<Fronteira> trechos = new ArrayList<>();
    for (final Fronteira fronteira : consultas) {
      if (trechos.isEmpty() || trechos.getLast() != fronteira) {
        trechos.add(fronteira);
      }
    }
    return trechos;
  }
}
