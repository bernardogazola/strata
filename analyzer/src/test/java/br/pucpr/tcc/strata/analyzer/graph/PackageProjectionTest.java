package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.PACKAGES;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.compute;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.declaredPackage;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.golden;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.goldenInput;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.ids;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.packageMetrics;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.pkg;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.resolved;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.row;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.type;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.junit.jupiter.api.Test;

final class PackageProjectionTest {

  private static final CodeElementId APP = pkg("com.x.app");
  private static final CodeElementId BILLING = pkg("com.x.billing");
  private static final CodeElementId ORDERS = pkg("com.x.orders");
  private static final CodeElementId ORDERS_DTO = pkg("com.x.orders.dto");

  @Test
  void osCiclosDeTipoSaoExatamenteOsDoGolden() throws IOException {
    final JsonNode golden = golden("cyclic");
    final JsonNode cycles = golden.get("cycles");
    final Map<CodeElementId, Integer> expectedSizes = new TreeMap<>();
    final Set<Set<CodeElementId>> expectedCycles = new HashSet<>();
    for (final JsonNode listed : cycles.get("types")) {
      final List<CodeElementId> members = ids(listed);
      members.forEach(member -> expectedSizes.put(member, members.size()));
      expectedCycles.add(Set.copyOf(members));
    }

    final StructuralGraphResult result = compute(goldenInput(golden));

    assertThat(result.types())
        .extracting(TypeMetrics::id)
        .containsExactlyElementsOf(goldenTypeIds(golden));
    assertThat(cycleSizes(result.types(), TypeMetrics::id, TypeMetrics::cycle))
        .isEqualTo(expectedSizes);
    assertThat(
            Set.copyOf(
                membersByCycle(result.types(), TypeMetrics::id, TypeMetrics::cycle).values()))
        .isEqualTo(expectedCycles);
    for (final CodeElementId outside : ids(cycles.get("notInAnyCycle"))) {
      assertThat(StructuralGraphInputs.typeMetrics(result, outside.sortKey()).inCycle())
          .as("%s está fora de todo ciclo", outside)
          .isEmpty();
    }
    assertThat(result.counters().typeCycles()).isEqualTo(cycles.get("types").size());
    assertThat(result.counters().typesInCycles()).isEqualTo(expectedSizes.size());
  }

  @Test
  void oCicloDePacoteEOrdersComOrdersDtoEMaisNenhum() throws IOException {
    final JsonNode golden = golden("cyclic");
    final Map<CodeElementId, Integer> expectedSizes = new TreeMap<>();
    final Set<Set<CodeElementId>> expectedCycles = new HashSet<>();
    for (final JsonNode listed : golden.get("cycles").get("packages")) {
      final List<CodeElementId> members = ids(listed);
      members.forEach(member -> expectedSizes.put(member, members.size()));
      expectedCycles.add(Set.copyOf(members));
    }

    final StructuralGraphResult result = compute(goldenInput(golden));

    assertThat(cycleSizes(result.packages(), PackageMetrics::id, PackageMetrics::cycle))
        .isEqualTo(expectedSizes)
        .isEqualTo(Map.of(ORDERS, 2, ORDERS_DTO, 2));
    assertThat(
            Set.copyOf(
                membersByCycle(result.packages(), PackageMetrics::id, PackageMetrics::cycle)
                    .values()))
        .isEqualTo(expectedCycles);
    assertThat(result.counters().packageCycles()).isEqualTo(1);
    assertThat(result.counters().packagesInCycles()).isEqualTo(2);
  }

  @Test
  void aCoesaoInternaDeCadaPacoteEAdoGolden() throws IOException {
    final JsonNode golden = golden("cyclic");
    final Map<CodeElementId, Integer> expected = new TreeMap<>();
    golden
        .get("cycles")
        .get("internalCohesion")
        .properties()
        .forEach(
            entry -> expected.put(new CodeElementId(entry.getKey()), entry.getValue().asInt()));

    final StructuralGraphResult result = compute(goldenInput(golden));

    final Map<CodeElementId, Integer> actual = new TreeMap<>();
    result.packages().forEach(metrics -> actual.put(metrics.id(), metrics.internalCohesion()));
    assertThat(actual).isEqualTo(expected);
    assertThat(actual).isEqualTo(Map.of(APP, 0, BILLING, 3, ORDERS, 0, ORDERS_DTO, 0));
  }

  @Test
  void oNumeroDoCicloSegueOMenorMembro() throws IOException {
    final StructuralGraphResult result = compute(goldenInput(golden("cyclic")));

    assertThat(membersByCycle(result.types(), TypeMetrics::id, TypeMetrics::cycle))
        .isEqualTo(
            Map.of(
                1,
                Set.of(
                    type("com.x.billing.Invoice"),
                    type("com.x.billing.Ledger"),
                    type("com.x.billing.Posting")),
                2,
                Set.of(type("com.x.orders.Order"), type("com.x.orders.dto.OrderDto"))));
    assertThat(membersByCycle(result.packages(), PackageMetrics::id, PackageMetrics::cycle))
        .isEqualTo(Map.of(1, Set.of(ORDERS, ORDERS_DTO)));
  }

  @Test
  void stronglyConnectedNumeraPeloMenorMembroESemCicloDeUm() {
    final Graph<Integer, DefaultEdge> graph =
        graph(7, new int[][] {{0, 6}, {1, 2}, {2, 3}, {3, 4}, {4, 2}, {4, 5}, {6, 0}});

    final CycleAssignment cycles = GraphAlgorithms.stronglyConnected(graph);

    assertThat(cycles.size()).isEqualTo(7);
    assertThat(cycles.cycleOf(0)).contains(new Cycle(1, 2));
    assertThat(cycles.cycleOf(6)).contains(new Cycle(1, 2));
    assertThat(cycles.cycleOf(2)).contains(new Cycle(2, 3));
    assertThat(cycles.cycleOf(3)).contains(new Cycle(2, 3));
    assertThat(cycles.cycleOf(4)).contains(new Cycle(2, 3));
    assertThat(cycles.cycleOf(1)).isEmpty();
    assertThat(cycles.cycleOf(5)).isEmpty();
    assertThat(cycles.cycleCount()).isEqualTo(2);
    assertThat(cycles.verticesInCycles()).isEqualTo(5);
  }

  @Test
  void ordersEOrdersDtoSaoDoisNosDiferentes() throws IOException {
    final JsonNode golden = golden("cyclic");
    final Set<CodeElementId> derived = new TreeSet<>();
    goldenTypeIds(golden).forEach(id -> derived.add(declaredPackage(id)));
    assertThat(derived).containsExactlyElementsOf(ids(golden.get("packages")));

    final StructuralGraphResult result = compute(goldenInput(golden));

    assertThat(result.packages())
        .extracting(PackageMetrics::id)
        .containsExactly(APP, BILLING, ORDERS, ORDERS_DTO);
    assertThat(result.counters().packageNodes()).isEqualTo(4);
  }

  @Test
  void asArestasDePacoteDoCiclicoSaoTresENenhumaDoBilling() throws IOException {
    final StructuralGraphResult result = compute(goldenInput(golden("cyclic")));

    assertThat(result.packageEdges())
        .containsExactly(
            new PackageEdge(
                APP,
                ORDERS,
                1,
                Map.of(IMPORTS, 1, CALLS, 1, FIELD_TYPE, 1, PARAMETER_TYPE, 1),
                4,
                RESOLVED),
            new PackageEdge(
                ORDERS,
                ORDERS_DTO,
                1,
                Map.of(IMPORTS, 1, FIELD_TYPE, 1, PARAMETER_TYPE, 1),
                3,
                RESOLVED),
            new PackageEdge(
                ORDERS_DTO,
                ORDERS,
                1,
                Map.of(IMPORTS, 1, FIELD_TYPE, 1, PARAMETER_TYPE, 1),
                3,
                RESOLVED));
    assertThat(result.counters().packageEdges()).isEqualTo(3);
  }

  @Test
  void aQualidadeDaArestaDePacoteEAMaisFracaDosPares() {
    final GraphInput input =
        new GraphInput(
            List.of(node("q.X"), node("p.C"), node("p.B"), node("p.A")),
            List.of(
                row("p.A", "q.X", CALLS, 2, RESOLVED),
                resolved("p.B", "q.X", CALLS),
                row("p.B", "q.X", FIELD_TYPE, 1, INFERRED),
                resolved("p.C", "q.X", IMPORTS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.packageEdges())
        .containsExactly(
            new PackageEdge(
                pkg("p"), pkg("q"), 3, Map.of(IMPORTS, 1, CALLS, 3, FIELD_TYPE, 1), 5, INFERRED));
  }

  @Test
  void oPesoDaArestaDePacoteETypePairCountNaoCalls() {
    final GraphInput input =
        new GraphInput(
            List.of(
                node("q2.Z"),
                node("p1.A"),
                node("q2.Y"),
                node("p2.B"),
                node("q1.X"),
                node("q2.X"),
                node("p2.A")),
            List.of(
                row("p1.A", "q1.X", CALLS, 1_000, RESOLVED),
                resolved("p2.A", "q2.X", CALLS),
                resolved("p2.A", "q2.Y", CALLS),
                resolved("p2.A", "q2.Z", CALLS),
                resolved("p2.B", "q2.X", CALLS),
                resolved("p2.B", "q2.Y", CALLS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.packageEdges())
        .containsExactly(
            new PackageEdge(pkg("p1"), pkg("q1"), 1, Map.of(CALLS, 1_000), 1_000, RESOLVED),
            new PackageEdge(pkg("p2"), pkg("q2"), 5, Map.of(CALLS, 5), 5, RESOLVED));
    assertThat(result.packageEdges().getFirst().occurrences().get(CALLS)).isEqualTo(1_000);
    assertThat(
            result.packageEdges().stream()
                .max(Comparator.comparingInt(PackageEdge::typePairCount))
                .orElseThrow())
        .extracting(PackageEdge::source, PackageEdge::target)
        .containsExactly(pkg("p2"), pkg("q2"));
  }

  @Test
  void oFanDePacoteContaPacotes() {
    final GraphInput input =
        new GraphInput(
            List.of(node("r.Z"), node("q.Y"), node("q.X"), node("p.B"), node("p.A")),
            List.of(
                resolved("p.A", "q.X", CALLS),
                resolved("p.A", "q.Y", CALLS),
                resolved("p.B", "q.X", CALLS),
                resolved("p.B", "q.Y", FIELD_TYPE),
                resolved("p.B", "r.Z", CALLS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.packages())
        .extracting(PackageMetrics::id, PackageMetrics::fanIn, PackageMetrics::fanOut)
        .containsExactly(tuple(pkg("p"), 0, 2), tuple(pkg("q"), 1, 0), tuple(pkg("r"), 1, 0));
    assertThat(result.packageEdges())
        .extracting(PackageEdge::source, PackageEdge::target, PackageEdge::typePairCount)
        .containsExactly(tuple(pkg("p"), pkg("q"), 4), tuple(pkg("p"), pkg("r"), 1));
  }

  @Test
  void osComponentesFracosSaoNumeradosPeloMenorMembroComOsIsolados() {
    final GraphInput input = sixPackagesTwoEdges();

    final StructuralGraphResult result = compute(input);

    assertThat(result.packages())
        .extracting(
            PackageMetrics::id,
            PackageMetrics::fanIn,
            PackageMetrics::fanOut,
            PackageMetrics::componentId,
            PackageMetrics::cycle)
        .containsExactly(
            tuple(pkg(""), 0, 0, 1, null),
            tuple(pkg("a"), 1, 0, 2, null),
            tuple(pkg("b"), 0, 0, 3, null),
            tuple(pkg("c"), 0, 1, 4, null),
            tuple(pkg("d"), 0, 1, 2, null),
            tuple(pkg("e"), 1, 0, 4, null));
  }

  @Test
  void weakComponentsNumeraPeloMenorMembro() {
    final Graph<Integer, DefaultEdge> graph = graph(6, new int[][] {{2, 4}, {3, 0}});

    assertThat(GraphAlgorithms.weakComponents(graph)).containsExactly(1, 2, 3, 1, 3, 4);
  }

  @Test
  void aIntermediacaoEBrandesBrutoDirigidoNoGrafoDePacotes() {
    final StructuralGraphResult result = compute(diamondWithTail());

    assertThat(result.packages())
        .extracting(PackageMetrics::id, PackageMetrics::betweenness)
        .containsExactly(
            tuple(pkg("a"), 0.0),
            tuple(pkg("b"), 1.0),
            tuple(pkg("c"), 1.0),
            tuple(pkg("d"), 3.0),
            tuple(pkg("e"), 0.0),
            tuple(pkg("s"), 0.0));
    assertThat(result.counters().betweennessSkipped()).isZero();
  }

  @Test
  void acimaDoTetoAIntermediacaoFicaNulaEOContadorMarca() {
    final GraphInput input = diamondWithTail();

    final StructuralGraphResult skipped =
        StructuralGraphAnalysis.compute(
            input, PACKAGES, () -> false, AnalysisConstants.PAGERANK_MAX_ITER, 5);
    final StructuralGraphResult atCap =
        StructuralGraphAnalysis.compute(
            input, PACKAGES, () -> false, AnalysisConstants.PAGERANK_MAX_ITER, 6);

    assertThat(skipped.packages())
        .hasSize(6)
        .extracting(PackageMetrics::betweenness)
        .containsOnlyNulls();
    assertThat(skipped.counters().betweennessSkipped()).isEqualTo(1);
    assertThat(packageMetrics(atCap, "d").betweenness()).isEqualTo(3.0);
    assertThat(atCap.counters().betweennessSkipped()).isZero();
  }

  @Test
  void betweennessDevolveOsValoresAteOTetoEVazioAcimaDele() {
    final Graph<Integer, DefaultEdge> graph =
        graph(6, new int[][] {{0, 1}, {0, 2}, {1, 3}, {2, 3}, {3, 4}});

    assertThat(GraphAlgorithms.betweenness(graph, 6))
        .hasValueSatisfying(
            values -> assertThat(values).containsExactly(0.0, 1.0, 1.0, 3.0, 0.0, 0.0));
    assertThat(GraphAlgorithms.betweenness(graph, 5)).isEmpty();
  }

  @Test
  void asMedidasDePacoteDoCiclicoSaoAsDerivadasAMao() throws IOException {
    final StructuralGraphResult result = compute(goldenInput(golden("cyclic")));

    final Cycle ordersCycle = new Cycle(1, 2);
    assertThat(result.packages())
        .extracting(
            PackageMetrics::id,
            PackageMetrics::fanIn,
            PackageMetrics::fanOut,
            PackageMetrics::cycle,
            PackageMetrics::componentId,
            PackageMetrics::internalCohesion,
            PackageMetrics::betweenness)
        .containsExactly(
            tuple(APP, 0, 1, null, 1, 0, 0.0),
            tuple(BILLING, 0, 0, null, 2, 3, 0.0),
            tuple(ORDERS, 2, 1, ordersCycle, 1, 0, 1.0),
            tuple(ORDERS_DTO, 1, 1, ordersCycle, 1, 0, 0.0));
  }

  @Test
  void osContadoresDoCiclicoSaoOsDerivadosAMao() throws IOException {
    final StructuralGraphResult result = compute(goldenInput(golden("cyclic")));

    assertThat(result.counters())
        .isEqualTo(new GraphCounters(6, 6, 0, 0, 0, 4, 3, 2, 5, 1, 2, 0, 0));
  }

  @Test
  void cadaVetorDePacoteSomaUmSozinho() throws IOException {
    final double sumTolerance = 1e-9;
    for (final GraphInput input :
        List.of(goldenInput(golden("cyclic")), sixPackagesTwoEdges(), diamondWithTail())) {
      final StructuralGraphResult result = compute(input);

      assertThat(sum(result.packages(), PackageMetrics::pagerank))
          .isCloseTo(1.0, within(sumTolerance));
      assertThat(sum(result.packages(), PackageMetrics::pagerankOut))
          .isCloseTo(1.0, within(sumTolerance));
      assertThat(sum(result.types(), TypeMetrics::pagerank)).isCloseTo(1.0, within(sumTolerance));
      assertThat(sum(result.types(), TypeMetrics::pagerankOut))
          .isCloseTo(1.0, within(sumTolerance));
    }

    final StructuralGraphResult cyclic = compute(goldenInput(golden("cyclic")));
    final double damping = AnalysisConstants.PAGERANK_DAMPING;
    final double bound =
        damping / (1 - damping) * cyclic.packages().size() * AnalysisConstants.PAGERANK_TOLERANCE;
    final PackageMetrics billing = packageMetrics(cyclic, "com.x.billing");
    assertThat(billing.pagerank()).isCloseTo(1 / 21.0, within(bound));
    assertThat(billing.pagerankOut()).isCloseTo(511 / 4271.0, within(bound));
  }

  private static GraphInput sixPackagesTwoEdges() {
    return new GraphInput(
        List.of(node("e.E"), node("d.D"), node("c.C"), node("b.B"), node("a.A"), node("Lonely")),
        List.of(resolved("d.D", "a.A", CALLS), resolved("c.C", "e.E", FIELD_TYPE)));
  }

  private static GraphInput diamondWithTail() {
    return new GraphInput(
        List.of(node("s.S"), node("e.E"), node("d.D"), node("c.C"), node("b.B"), node("a.A")),
        List.of(
            resolved("a.A", "b.B", CALLS),
            resolved("a.A", "c.C", CALLS),
            resolved("b.B", "d.D", CALLS),
            resolved("c.C", "d.D", CALLS),
            resolved("d.D", "e.E", CALLS)));
  }

  private static Graph<Integer, DefaultEdge> graph(int vertices, int[][] edges) {
    final Graph<Integer, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    for (int vertex = 0; vertex < vertices; vertex++) {
      graph.addVertex(vertex);
    }
    for (final int[] edge : edges) {
      graph.addEdge(edge[0], edge[1]);
    }
    return graph;
  }

  private static List<CodeElementId> goldenTypeIds(JsonNode golden) {
    final List<CodeElementId> ids = new ArrayList<>();
    for (final JsonNode type : golden.get("types")) {
      ids.add(new CodeElementId(type.get("id").asText()));
    }
    return ids;
  }

  private static <T> Map<CodeElementId, Integer> cycleSizes(
      List<T> rows, Function<T, CodeElementId> id, Function<T, Cycle> cycle) {
    final Map<CodeElementId, Integer> sizes = new TreeMap<>();
    for (final T row : rows) {
      final Cycle membership = cycle.apply(row);
      if (membership != null) {
        sizes.put(id.apply(row), membership.size());
      }
    }
    return sizes;
  }

  private static <T> Map<Integer, Set<CodeElementId>> membersByCycle(
      List<T> rows, Function<T, CodeElementId> id, Function<T, Cycle> cycle) {
    final Map<Integer, Set<CodeElementId>> members = new TreeMap<>();
    for (final T row : rows) {
      final Cycle membership = cycle.apply(row);
      if (membership != null) {
        members.computeIfAbsent(membership.id(), unused -> new TreeSet<>()).add(id.apply(row));
      }
    }
    return members;
  }

  private static <T> double sum(List<T> rows, Function<T, Double> value) {
    double total = 0;
    for (final T row : rows) {
      total += value.apply(row);
    }
    return total;
  }
}
