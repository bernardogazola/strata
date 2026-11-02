package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.NEVER_TARGETED_SHARE;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.SINK_SHARE;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.internalEdges;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.jgraphtGraph;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.jgraphtPageRank;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.permutation;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.rawBits;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.shuffled;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.sortedIds;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.source;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.target;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPLEMENTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.RETURN_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.RankVector;
import br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.Synthetic;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class PageRankTest {

  private static final ExternalPackages PACKAGES = ExternalPackages.load();
  private static final BooleanSupplier NOT_CANCELLED = () -> false;

  private static final double SUM_TOLERANCE = 1e-9;

  private static final String GOLDEN_6_RESOURCE = "/graph/golden-6.json";

  private static final int GOLDEN_6_FORWARD_ITERATIONS = 17;

  private static final int GOLDEN_6_REVERSED_ITERATIONS = 24;

  private static final long FORWARD_DENOMINATOR = 13_818_887L;

  private static final long REVERSED_DENOMINATOR = 24_214_121L;

  private static final List<Golden> GOLDEN_6 =
      List.of(
          new Golden(
              "App",
              1_280_000 / (double) FORWARD_DENOMINATOR,
              9_279_861 / (double) REVERSED_DENOMINATOR),
          new Golden(
              "Clock",
              2_178_620 / (double) FORWARD_DENOMINATOR,
              1_920_000 / (double) REVERSED_DENOMINATOR),
          new Golden(
              "Controller",
              1_552_000 / (double) FORWARD_DENOMINATOR,
              4_303_060 / (double) REVERSED_DENOMINATOR),
          new Golden(
              "Money",
              4_418_047 / (double) FORWARD_DENOMINATOR,
              1_920_000 / (double) REVERSED_DENOMINATOR),
          new Golden(
              "Repository",
              2_178_620 / (double) FORWARD_DENOMINATOR,
              2_464_000 / (double) REVERSED_DENOMINATOR),
          new Golden(
              "Service",
              2_211_600 / (double) FORWARD_DENOMINATOR,
              4_327_200 / (double) REVERSED_DENOMINATOR));

  private static final List<String> GOLDEN_6_NAMES =
      List.of("App", "Controller", "Service", "Repository", "Money", "Clock");

  private static final List<Row> GOLDEN_6_ROWS =
      List.of(
          new Row("App", "Controller", FIELD_TYPE, RESOLVED, 1),
          new Row("App", "Controller", CALLS, RESOLVED, 2),
          new Row("App", "Service", FIELD_TYPE, RESOLVED, 1),
          new Row("App", "Service", CALLS, RESOLVED, 5),
          new Row("App", "Repository", FIELD_TYPE, RESOLVED, 1),
          new Row("App", "Clock", IMPORTS, RESOLVED, 1),
          new Row("App", "Clock", CALLS, RESOLVED, 1),
          new Row("Controller", "Service", FIELD_TYPE, RESOLVED, 1),
          new Row("Controller", "Service", CALLS, RESOLVED, 7),
          new Row("Controller", "Money", PARAMETER_TYPE, RESOLVED, 2),
          new Row("Controller", "Money", RETURN_TYPE, RESOLVED, 1),
          new Row("Service", "Repository", FIELD_TYPE, RESOLVED, 1),
          new Row("Service", "Repository", CALLS, RESOLVED, 4),
          new Row("Service", "Money", CALLS, INFERRED, 3),
          new Row("Service", "Clock", FIELD_TYPE, RESOLVED, 1),
          new Row("Service", "Clock", CALLS, RESOLVED, 1),
          new Row("Repository", "Money", RETURN_TYPE, RESOLVED, 2));

  enum Layout {
    ONE_PACKAGE,
    TWO_PACKAGES;

    String packageOf(String name) {
      return switch (this) {
        case ONE_PACKAGE -> "com.acme";
        case TWO_PACKAGES ->
            "App".equals(name) || "Controller".equals(name) ? "com.acme.web" : "com.acme.core";
      };
    }

    CodeElementId type(String name) {
      return CodeElementId.ofType(packageOf(name) + "." + name);
    }

    CodeElementId pkg(String name) {
      return CodeElementId.ofPackage(packageOf(name));
    }
  }

  private static GraphInput golden6(Layout layout) {
    final List<TypeNode> types =
        GOLDEN_6_NAMES.stream()
            .map(name -> new TypeNode(layout.type(name), layout.pkg(name)))
            .toList();
    final List<Dependency> rows = new ArrayList<>();
    for (final Row row : GOLDEN_6_ROWS) {
      rows.add(
          new Dependency(
              layout.type(row.source()),
              layout.type(row.target()),
              row.type(),
              row.resolution(),
              row.occurrences()));
    }
    rows.add(new Dependency(layout.type("Service"), layout.type("Service"), CALLS, RESOLVED, 2));
    rows.add(
        new Dependency(
            layout.type("App"), CodeElementId.ofType("java.lang.String"), CALLS, RESOLVED, 1));
    rows.add(
        new Dependency(
            layout.type("Repository"),
            CodeElementId.ofType("org.lib.Store"),
            IMPLEMENTS,
            UNRESOLVED_EXTERNAL,
            1));
    return new GraphInput(types, rows);
  }

  @Test
  void oGrafoQueOOraculoLeEOGolden6DesteTeste() throws IOException {
    final JsonNode json;
    try (InputStream in = PageRankTest.class.getResourceAsStream(GOLDEN_6_RESOURCE)) {
      assertThat(in).as(GOLDEN_6_RESOURCE).isNotNull();
      json = new ObjectMapper().readTree(in);
    }
    final GraphInput input = golden6(Layout.ONE_PACKAGE);
    final List<CodeElementId> sorted = sortedIds(input);
    final List<String> edges = new ArrayList<>();
    for (final long edge : internalEdges(input)) {
      edges.add(sorted.get(source(edge)).value() + " -> " + sorted.get(target(edge)).value());
    }

    final List<String> jsonVertices = new ArrayList<>();
    json.get("vertices").forEach(vertex -> jsonVertices.add(vertex.asText()));
    final List<String> jsonEdges = new ArrayList<>();
    json.get("edges")
        .forEach(edge -> jsonEdges.add(edge.get(0).asText() + " -> " + edge.get(1).asText()));

    assertThat(jsonVertices)
        .containsExactlyElementsOf(sorted.stream().map(CodeElementId::value).toList());
    assertThat(jsonEdges).hasSize(10).containsExactlyElementsOf(edges);
  }

  @ParameterizedTest
  @EnumSource(Layout.class)
  void oGolden6BateComOOraculoExatoNosDoisSentidos(Layout layout) {
    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(golden6(layout), PACKAGES, NOT_CANCELLED);

    final double bound = errorBound(GOLDEN_6.size());
    assertThat(result.counters().pagerankUnconverged()).isZero();
    assertThat(result.types()).hasSize(GOLDEN_6.size());
    for (final Golden golden : GOLDEN_6) {
      final TypeMetrics metrics = type(result, layout.type(golden.name()));
      assertThat(metrics.pagerank())
          .as("pagerank de %s", golden.name())
          .isCloseTo(golden.pagerank(), within(bound));
      assertThat(metrics.pagerankOut())
          .as("pagerankOut de %s", golden.name())
          .isCloseTo(golden.pagerankOut(), within(bound));
    }
  }

  @Test
  void oVetorDePacotesDoGolden6EmDoisPacotesBateComAContaAMao() {
    final Layout layout = Layout.TWO_PACKAGES;
    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(golden6(layout), PACKAGES, NOT_CANCELLED);

    final double bound = errorBound(2);
    final PackageMetrics core = pkg(result, layout.pkg("Service"));
    final PackageMetrics web = pkg(result, layout.pkg("App"));
    assertThat(result.packages()).hasSize(2);
    assertThat(core.pagerank()).isCloseTo(37 / 57d, within(bound));
    assertThat(web.pagerank()).isCloseTo(20 / 57d, within(bound));
    assertThat(core.pagerankOut()).isCloseTo(20 / 57d, within(bound));
    assertThat(web.pagerankOut()).isCloseTo(37 / 57d, within(bound));
  }

  @ParameterizedTest
  @EnumSource(Layout.class)
  void empatesExatosDoOraculoSaoEmpatesDeBits(Layout layout) {
    assertThat(golden("Repository").pagerank()).isEqualTo(golden("Clock").pagerank());
    assertThat(golden("Money").pagerankOut()).isEqualTo(golden("Clock").pagerankOut());

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(golden6(layout), PACKAGES, NOT_CANCELLED);

    final TypeMetrics repository = type(result, layout.type("Repository"));
    final TypeMetrics clock = type(result, layout.type("Clock"));
    final TypeMetrics money = type(result, layout.type("Money"));
    assertThat(Double.doubleToRawLongBits(repository.pagerank()))
        .as("pagerank de Repository e de Clock")
        .isEqualTo(Double.doubleToRawLongBits(clock.pagerank()));
    assertThat(Double.doubleToRawLongBits(money.pagerankOut()))
        .as("pagerankOut de Money e de Clock")
        .isEqualTo(Double.doubleToRawLongBits(clock.pagerankOut()));
  }

  @ParameterizedTest
  @EnumSource(Layout.class)
  void oPagerankPoeOMaisRequisitadoPrimeiroEOInvertidoNao(Layout layout) {
    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(golden6(layout), PACKAGES, NOT_CANCELLED);

    final TypeMetrics money = type(result, layout.type("Money"));
    final TypeMetrics app = type(result, layout.type("App"));
    for (final TypeMetrics other : result.types()) {
      if (!other.id().equals(money.id())) {
        assertThat(money.pagerank())
            .as("Money acima de %s", other.id())
            .isGreaterThan(other.pagerank());
      }
      if (!other.id().equals(app.id())) {
        assertThat(app.pagerankOut())
            .as("no invertido, App acima de %s", other.id())
            .isGreaterThan(other.pagerankOut());
      }
      assertThat(money.pagerankOut())
          .as("no invertido, Money em último, empatado com Clock")
          .isLessThanOrEqualTo(other.pagerankOut());
    }
  }

  @Test
  void cadaVetorSomaUmComPendentesEComponentesDesconexos() {
    final List<TypeNode> types = new ArrayList<>();
    final List<Dependency> rows = new ArrayList<>();
    final Map<String, CodeElementId> ids = new HashMap<>();
    for (final String type :
        List.of(
            "alpha.Hub",
            "alpha.Leaf1",
            "beta.Leaf2",
            "gamma.Leaf3",
            "beta.Chain1",
            "beta.Chain2",
            "gamma.Chain3",
            "gamma.Chain4",
            "gamma.CycleA",
            "gamma.CycleB",
            "delta.CycleC",
            "delta.Tail",
            "epsilon.Isolated")) {
      final String qualified = "com." + type;
      final CodeElementId id = CodeElementId.ofType(qualified);
      ids.put(type.substring(type.indexOf('.') + 1), id);
      types.add(
          new TypeNode(
              id, CodeElementId.ofPackage(qualified.substring(0, qualified.lastIndexOf('.')))));
    }
    for (final String[] edge :
        new String[][] {
          {"Leaf1", "Hub"},
          {"Leaf2", "Hub"},
          {"Leaf3", "Hub"},
          {"Chain1", "Chain2"},
          {"Chain2", "Chain3"},
          {"Chain3", "Chain4"},
          {"CycleA", "CycleB"},
          {"CycleB", "CycleC"},
          {"CycleC", "CycleA"},
          {"CycleC", "Tail"}
        }) {
      rows.add(new Dependency(ids.get(edge[0]), ids.get(edge[1]), CALLS, RESOLVED, 1));
    }

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(new GraphInput(types, rows), PACKAGES, NOT_CANCELLED);

    assertThat(result.types()).hasSize(13);
    assertThat(result.packages()).hasSize(5);
    assertSumsToOne(result);
  }

  @Test
  void cadaVetorSomaUmNoGrafoSinteticoDeTresMilTipos() {
    final Synthetic graph = synthetic();

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(graph.input(), PACKAGES, NOT_CANCELLED);

    assertThat(result.types()).hasSize(SYNTHETIC_TYPES);
    assertThat(result.packages()).hasSize(SYNTHETIC_PACKAGES);
    assertThat(result.counters().pagerankUnconverged()).isZero();
    assertSumsToOne(result);
  }

  @Test
  void aOrdemDeChegadaDasLinhasNaoMexeEmNenhumBit() {
    final Synthetic graph = synthetic();
    final StructuralGraphResult baseline =
        StructuralGraphAnalysis.compute(graph.input(), PACKAGES, NOT_CANCELLED);

    for (final long seed : SHUFFLE_SEEDS) {
      final GraphInput shuffled =
          new GraphInput(
              shuffled(graph.input().types(), seed), shuffled(graph.input().rows(), seed + 1));
      final StructuralGraphResult again =
          StructuralGraphAnalysis.compute(shuffled, PACKAGES, NOT_CANCELLED);

      for (final RankVector vector : RankVector.values()) {
        assertThat(rawBits(vector.inIdOrder(again)))
            .as("%s com a semente %d", vector, seed)
            .isEqualTo(rawBits(vector.inIdOrder(baseline)));
      }
      assertThat(again).as("o resultado inteiro com a semente %d", seed).isEqualTo(baseline);
    }
  }

  @Test
  void inserirForaDeOrdemMudaOsBitsDoJGraphT() {
    final Synthetic graph = synthetic();
    final int n = graph.input().types().size();
    final SplittableRandom random = new SplittableRandom(CONTROL_SEED);
    final int[] identity = new int[n];
    Arrays.setAll(identity, vertex -> vertex);

    final Graph<Integer, DefaultEdge> sorted = jgraphtGraph(identity, graph.edges());
    final Graph<Integer, DefaultEdge> unsorted =
        jgraphtGraph(permutation(n, random), shuffled(graph.edges(), random));

    for (final boolean reversed : new boolean[] {false, true}) {
      final double[] inOrder = jgraphtPageRank(reversed ? new EdgeReversedGraph<>(sorted) : sorted);
      final double[] outOfOrder =
          jgraphtPageRank(reversed ? new EdgeReversedGraph<>(unsorted) : unsorted);
      int differing = 0;
      double largest = 0;
      for (int vertex = 0; vertex < n; vertex++) {
        if (Double.doubleToRawLongBits(inOrder[vertex])
            != Double.doubleToRawLongBits(outOfOrder[vertex])) {
          differing++;
        }
        largest = Math.max(largest, Math.abs(inOrder[vertex] - outOfOrder[vertex]));
      }
      assertThat(differing).as("vértices com outro double, invertido = %s", reversed).isPositive();
      assertThat(largest)
          .as("a maior diferença, invertido = %s", reversed)
          .isLessThan(ROUNDING_ONLY);
    }
  }

  @Test
  void oTetoUmAbaixoDoQueOGolden6PrecisaNaoConvergeEONecessarioConverge() {
    final GraphInput input = golden6(Layout.ONE_PACKAGE);
    final int[] identity = {0, 1, 2, 3, 4, 5};
    final Graph<Integer, DefaultEdge> forward = jgraphtGraph(identity, internalEdges(input));
    final Graph<Integer, DefaultEdge> reversed = new EdgeReversedGraph<>(forward);
    final int k = GOLDEN_6_FORWARD_ITERATIONS;

    assertThat(GraphAlgorithms.pageRank(forward, k - 1).converged()).isFalse();
    assertThat(GraphAlgorithms.pageRank(forward, k).converged()).isTrue();
    assertThat(rawBits(GraphAlgorithms.pageRank(forward, k).toArray()))
        .isEqualTo(
            rawBits(
                GraphAlgorithms.pageRank(forward, AnalysisConstants.PAGERANK_MAX_ITER).toArray()));
    assertThat(GraphAlgorithms.pageRank(reversed, GOLDEN_6_REVERSED_ITERATIONS - 1).converged())
        .isFalse();
    assertThat(GraphAlgorithms.pageRank(reversed, GOLDEN_6_REVERSED_ITERATIONS).converged())
        .isTrue();

    final StructuralGraphResult capped =
        StructuralGraphAnalysis.compute(
            input, PACKAGES, NOT_CANCELLED, k - 1, AnalysisConstants.BETWEENNESS_MAX_NODES);
    final StructuralGraphResult production =
        StructuralGraphAnalysis.compute(input, PACKAGES, NOT_CANCELLED);
    assertThat(capped.counters().pagerankUnconverged()).isEqualTo(2);
    assertThat(production.counters().pagerankUnconverged()).isZero();
  }

  @Test
  void semTipoNenhumOResultadoEVazio() {
    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(
            new GraphInput(List.of(), List.of()), PACKAGES, NOT_CANCELLED);

    assertThat(result.types()).isEmpty();
    assertThat(result.packages()).isEmpty();
    assertThat(result.packageEdges()).isEmpty();
    assertThat(result.counters()).isEqualTo(GraphCounters.EMPTY);
  }

  @Test
  void umTipoSemArestaTemPagerankUmNosDoisSentidos() {
    final CodeElementId alone = CodeElementId.ofType("com.acme.Alone");
    final GraphInput input =
        new GraphInput(
            List.of(new TypeNode(alone, CodeElementId.ofPackage("com.acme"))), List.of());

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(input, PACKAGES, NOT_CANCELLED);

    assertThat(result.types()).hasSize(1);
    assertThat(type(result, alone).pagerank()).isEqualTo(1.0d);
    assertThat(type(result, alone).pagerankOut()).isEqualTo(1.0d);
    assertThat(result.packages()).hasSize(1);
    assertThat(result.packages().getFirst().pagerank()).isEqualTo(1.0d);
    assertThat(result.packages().getFirst().pagerankOut()).isEqualTo(1.0d);
  }

  @Test
  void umTipoSoComLinhaParaSiMesmoTemPagerankUmEUmaAutolinha() {
    final CodeElementId self = CodeElementId.ofType("com.acme.Recursive");
    final GraphInput input =
        new GraphInput(
            List.of(new TypeNode(self, CodeElementId.ofPackage("com.acme"))),
            List.of(new Dependency(self, self, CALLS, RESOLVED, 3)));

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(input, PACKAGES, NOT_CANCELLED);

    assertThat(type(result, self).pagerank()).isEqualTo(1.0d);
    assertThat(type(result, self).pagerankOut()).isEqualTo(1.0d);
    assertThat(result.counters().selfPairs()).isEqualTo(1);
    assertThat(result.counters().typeEdges()).isZero();
  }

  @Test
  void umaAutolinhaNaoViraLacoEmDoisTipos() {
    final CodeElementId callee = CodeElementId.ofType("com.acme.Callee");
    final CodeElementId caller = CodeElementId.ofType("com.acme.Caller");
    final CodeElementId acme = CodeElementId.ofPackage("com.acme");
    final GraphInput input =
        new GraphInput(
            List.of(new TypeNode(caller, acme), new TypeNode(callee, acme)),
            List.of(
                new Dependency(caller, callee, CALLS, RESOLVED, 1),
                new Dependency(callee, callee, CALLS, RESOLVED, 4)));

    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(input, PACKAGES, NOT_CANCELLED);

    final double bound = errorBound(2);
    assertThat(type(result, callee).pagerank()).isCloseTo(37 / 57d, within(bound));
    assertThat(type(result, caller).pagerank()).isCloseTo(20 / 57d, within(bound));
    assertThat(type(result, callee).pagerankOut()).isCloseTo(20 / 57d, within(bound));
    assertThat(type(result, caller).pagerankOut()).isCloseTo(37 / 57d, within(bound));
    assertThat(result.counters().selfPairs()).isEqualTo(1);
    assertThat(result.counters().typeEdges()).isEqualTo(1);
  }

  static final int SYNTHETIC_TYPES = 3_000;
  static final int SYNTHETIC_PACKAGES = 40;
  private static final long SYNTHETIC_SEED = 30L;
  private static final long[] SHUFFLE_SEEDS = {1L, 30L, 2_026L};
  private static final long CONTROL_SEED = 3_000L;

  private static final double ROUNDING_ONLY = 1e-12;

  @Test
  void oGrafoSinteticoTemAFormaDeUmRepositorio() {
    final Synthetic graph = synthetic();
    final int n = graph.input().types().size();
    final int[] fanOut = new int[n];
    final int[] fanIn = new int[n];
    for (final long edge : graph.edges()) {
      fanOut[source(edge)]++;
      fanIn[target(edge)]++;
    }

    assertThat(n).isGreaterThanOrEqualTo(3_000);
    assertThat(graph.input().types().stream().map(TypeNode::packageId).distinct().count())
        .isGreaterThanOrEqualTo(30);
    assertThat(Arrays.stream(fanOut).filter(degree -> degree == 0).count())
        .isGreaterThanOrEqualTo(Math.round(n * SINK_SHARE));
    assertThat(Arrays.stream(fanIn).filter(degree -> degree == 0).count())
        .isGreaterThanOrEqualTo(Math.round(n * NEVER_TARGETED_SHARE));
    assertThat((double) Arrays.stream(fanIn).max().orElseThrow())
        .isGreaterThan(20d * graph.edges().length / n);
    assertThat(graph.input().rows())
        .anyMatch(row -> row.source().equals(row.target()))
        .anyMatch(row -> !graph.isInternal(row.target()));
  }

  static Synthetic synthetic() {
    return SyntheticGraphs.generate(SYNTHETIC_TYPES, SYNTHETIC_PACKAGES, SYNTHETIC_SEED);
  }

  record Golden(String name, double pagerank, double pagerankOut) {}

  record Row(
      String source,
      String target,
      DependencyType type,
      ResolutionQuality resolution,
      int occurrences) {}

  private static Golden golden(String name) {
    return GOLDEN_6.stream()
        .filter(golden -> golden.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhum golden para " + name));
  }

  private static double errorBound(int vertices) {
    return AnalysisConstants.PAGERANK_DAMPING
        / (1 - AnalysisConstants.PAGERANK_DAMPING)
        * vertices
        * AnalysisConstants.PAGERANK_TOLERANCE;
  }

  private static void assertSumsToOne(StructuralGraphResult result) {
    for (final RankVector vector : RankVector.values()) {
      double sum = 0;
      for (final double value : vector.inIdOrder(result)) {
        sum += value;
      }
      assertThat(sum).as("Σ %s", vector).isCloseTo(1.0d, within(SUM_TOLERANCE));
    }
  }

  private static TypeMetrics type(StructuralGraphResult result, CodeElementId id) {
    return result.types().stream()
        .filter(metrics -> metrics.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhuma métrica de tipo para " + id));
  }

  private static PackageMetrics pkg(StructuralGraphResult result, CodeElementId id) {
    return result.packages().stream()
        .filter(metrics -> metrics.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhuma métrica de pacote para " + id));
  }
}
