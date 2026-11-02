package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.jgraphtGraph;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.jgraphtPageRank;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.permutation;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.rawBits;
import static br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.shuffled;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Baseline;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Environment;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Host;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.SelectionPayload;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.GraphAlgorithms;
import br.pucpr.tcc.strata.analyzer.graph.GraphInput;
import br.pucpr.tcc.strata.analyzer.graph.PackageMetrics;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphAnalysis;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphBuilder;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphResult;
import br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs;
import br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.RankVector;
import br.pucpr.tcc.strata.analyzer.graph.SyntheticGraphs.Synthetic;
import br.pucpr.tcc.strata.analyzer.graph.TypeMetrics;
import br.pucpr.tcc.strata.analyzer.graph.TypeNode;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphInputDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphSource;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.HeapPeakMeter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class PageRankDeterminismSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final String REPORT = "benchmarks/pagerank-determinism.json";
  private static final String BASELINE = "benchmarks/stages-baseline.json";
  private static final String DUMPS = "analyzer/target/pagerank-determinism";
  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final String COMPLETED = "COMPLETED";
  private static final String ANALYSIS_COMPLETED = "analysis.completed";
  private static final String STAGE = AnalysisStage.STRUCTURAL_GRAPH.name();
  private static final int RUNS = 2;
  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);
  private static final ExternalPackages PACKAGES = ExternalPackages.load();
  private static final BooleanSupplier NOT_CANCELLED = () -> false;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HexFormat HEX = HexFormat.of();

  private static final double SUM_TOLERANCE = 1e-9;

  private static final long SYNTHETIC_SEED = 30L;

  private static final int TYPES_PER_PACKAGE = 75;

  private static final List<Integer> SYNTHETIC_SIZES = List.of(3_000, 30_000, 100_000);

  private static final long[] SHUFFLE_SEEDS = {1L, 2L, 3L, 4L, 5L};

  private static final long ROW_SEED_OFFSET = 100L;

  private static final long CONTROL_SEED = 3_000L;

  private static final double ROUNDING_ONLY = 1e-12;

  private static final int CONTROL_TYPES = 3_000;

  private static final long CONTROL_DIFFERING_G = 2_998;
  private static final long CONTROL_DIFFERING_G_REV = 2_977;
  private static final long CONTROL_MAX_ULPS = 28;

  private static final int SIGNIFICANT_DIGITS = 17;

  private static final double COARSE_TOLERANCE = 1e-6;
  private static final double REFERENCE_TOLERANCE = 1e-15;
  private static final int REFERENCE_CAP = 2_000;
  private static final int TOLERANCE_SYNTHETIC_TYPES = 30_000;

  private static final int CONTRACTION_BOUND_ITERATIONS = 147;

  private static final long MAX_PERCENTILES_CHANGED_AT_PRODUCTION = 2;

  private static final int ES_COARSE_ITERATIONS_G = 34;

  private static final int ES_COARSE_ITERATIONS_G_REV = 41;

  private static final int TENTHS = 10;

  private static final double PERCENT = 100.0;

  private static final String DERIVED = "broken-tree";
  private static final String LARGEST = "elasticsearch";
  private static final List<String> REPOSITORIES =
      List.of("commons-lang", DERIVED, "junit5", "spring-framework", LARGEST);
  private static final int TOP = 10;

  private static final StagePlan PLAN =
      Stages.production().with(AnalysisStage.STRUCTURAL_GRAPH, new StructuralGraphStage(PACKAGES));

  private static final String PLAN_TEXT =
      "Stages.production().with(STRUCTURAL_GRAPH, new"
          + " StructuralGraphStage(ExternalPackages.load()))";

  private static final String MEASURE =
      "PageRank determinism. Synthetic: SyntheticGraphs.generate(n, n / 75, 30) for n = 3000,"
          + " 30000, 100000; StructuralGraphAnalysis.compute over the generation order and over"
          + " five shuffled arrival orders of the same types and rows, every double compared as"
          + " raw bits; the control builds JGraphT directly, sorted and unsorted (PageRankTest's"
          + " control seed), and runs PageRank with the production constants. Tolerance: on the"
          + " 30000-type synthetic graph and on Elasticsearch ALL, both orientations, the"
          + " iterations JGraphT needs at 1e-6 and 1e-10 by bisecting the cap with the two-run"
          + " recipe (PR(cap) bitwise equal to PR(cap+1)), and the error against a reference"
          + " vector at 1e-15 (cap 2000). Corpus: five repositories x two selections, two"
          + " analyses each through the real AnalysisRegistry with the production plan plus the"
          + " STRUCTURAL_GRAPH stage, on fresh data directories; graph_metric and"
          + " package_edge dumped"
          + " by element_id with doubles as raw bits, compared byte for byte; sums, top ten and"
          + " one-top-level-type cycles are SQL over the persisted rows. Elasticsearch ALL's"
          + " second analysis also feeds the input it read back from the cache to compute in five"
          + " shuffled orders.";

  private static final Map<String, Census> CENSUS =
      Map.of(
          key("commons-lang", Selection.DEFAULT),
          new Census(
              364,
              721,
              18,
              50,
              23,
              70,
              131,
              1,
              13,
              13,
              603,
              1595,
              1,
              18,
              18,
              "type:org.apache.commons.lang3.ArrayUtils",
              "type:org.apache.commons.lang3.builder.ReflectionDiffBuilder"),
          key("commons-lang", Selection.ALL),
          new Census(
              958,
              2105,
              20,
              64,
              27,
              70,
              139,
              1,
              15,
              15,
              2096,
              3072,
              1,
              22,
              22,
              "type:org.apache.commons.lang3.builder.ToStringStyle",
              "type:org.apache.commons.lang3.function.FailableTest"),
          key(DERIVED, Selection.DEFAULT),
          new Census(
              362,
              708,
              18,
              49,
              24,
              67,
              130,
              1,
              13,
              13,
              599,
              1569,
              1,
              18,
              17,
              "type:org.apache.commons.lang3.ArrayUtils",
              "type:org.apache.commons.lang3.builder.ReflectionDiffBuilder"),
          key(DERIVED, Selection.ALL),
          new Census(
              956,
              2090,
              20,
              63,
              28,
              67,
              138,
              1,
              15,
              15,
              2094,
              3046,
              1,
              22,
              20,
              "type:org.apache.commons.lang3.builder.ToStringStyle",
              "type:org.apache.commons.lang3.function.FailableTest"),
          key("junit5", Selection.DEFAULT),
          new Census(
              1110,
              5017,
              76,
              384,
              59,
              15,
              184,
              6,
              6,
              25,
              3993,
              3532,
              4,
              23,
              45,
              "type:org.junit.platform.commons.util.Preconditions",
              "type:org.junit.jupiter.engine.JupiterTestEngine"),
          key("junit5", Selection.ALL),
          new Census(
              3751,
              16_246,
              114,
              876,
              62,
              15,
              191,
              4,
              55,
              63,
              7316,
              7336,
              1,
              26,
              68,
              "type:org.junit.platform.commons.util.Preconditions",
              "type:org.junit.platform.suite.engine.SuiteEngineTests"),
          key("spring-framework", Selection.DEFAULT),
          new Census(
              6923,
              36_067,
              571,
              4104,
              324,
              38,
              1266,
              14,
              9,
              51,
              20_650,
              25_553,
              11,
              209,
              53,
              "type:org.springframework.util.Assert",
              "type:org.springframework.test.web.reactive.server.WebTestClient"),
          key("spring-framework", Selection.ALL),
          new Census(
              18_468,
              78_453,
              817,
              7018,
              368,
              38,
              1375,
              20,
              337,
              420,
              44_582,
              43_149,
              21,
              247,
              61,
              "type:org.springframework.util.Assert",
              "type:org.springframework.expression.spel.standard.InternalSpelExpressionParser"),
          key(LARGEST, Selection.DEFAULT),
          new Census(
              26_883,
              236_817,
              2007,
              31_478,
              698,
              5756,
              12_265,
              80,
              438,
              1408,
              64_656,
              104_298,
              28,
              466,
              104,
              "type:org.elasticsearch.common.io.stream.StreamOutput",
              "type:org.elasticsearch.xpack.ml.MachineLearning"),
          key(LARGEST, Selection.ALL),
          new Census(
              39_364,
              380_071,
              2129,
              41_471,
              981,
              5756,
              12_995,
              44,
              1535,
              1724,
              115_154,
              153_655,
              23,
              730,
              121,
              "type:org.elasticsearch.common.io.stream.StreamOutput",
              "type:org.elasticsearch.xpack.ml.MachineLearning"));

  private static final String STAGE_ROW =
      "SELECT status, duration_ms, counters_json FROM analysis_stage"
          + " WHERE analysis_pk = ? AND stage = ?";

  private static final String GRAPH_METRIC_DUMP =
      """
      SELECT e.element_id, g.granularity, g.fan_in, g.fan_out, g.fan_in_resolved,
             g.fan_out_resolved, g.fan_out_external, g.external_fan_in, g.pagerank, g.pagerank_out,
             g.scc_id, g.scc_size, g.bridge_span, g.component_id, g.internal_cohesion,
             g.betweenness, g.is_entry_point, g.entry_point_kind
        FROM graph_metric g JOIN code_element e ON e.id = g.element_pk
       WHERE g.analysis_pk = ? ORDER BY e.element_id""";

  private static final List<Integer> REAL_COLUMNS = List.of(9, 10, 16);

  private static final int PAGERANK_FIELD = 8;
  private static final int PAGERANK_OUT_FIELD = 9;

  private static final String PACKAGE_EDGE_DUMP =
      """
      SELECT s.element_id, t.element_id, p.type_pair_count, p.occurrence_total, p.resolution,
             p.imports, p.extends, p.implements, p.calls, p.field_type, p.parameter_type,
             p.return_type, p.annotation_reference
        FROM package_edge p
        JOIN code_element s ON s.id = p.source_pk
        JOIN code_element t ON t.id = p.target_pk
       WHERE p.analysis_pk = ? ORDER BY s.element_id, t.element_id""";

  private static final String VECTORS =
      """
      SELECT g.pagerank, g.pagerank_out
        FROM graph_metric g JOIN code_element e ON e.id = g.element_pk
       WHERE g.analysis_pk = ? AND g.granularity = ? ORDER BY e.element_id""";

  private static final String TOP_BY =
      """
      SELECT e.element_id, g.%s, g.fan_in, g.fan_out
        FROM graph_metric g JOIN code_element e ON e.id = g.element_pk
       WHERE g.analysis_pk = ? AND g.granularity = 'TYPE'
       ORDER BY g.%s DESC, e.element_id LIMIT %d""";

  private static final String CYCLE_MEMBERS =
      """
      SELECT g.scc_id, e.qualified_name, p.qualified_name, p.kind
        FROM graph_metric g
        JOIN code_element e ON e.id = g.element_pk
        JOIN code_element p ON p.id = e.parent_pk
       WHERE g.analysis_pk = ? AND g.granularity = 'TYPE' AND g.scc_id IS NOT NULL
       ORDER BY g.scc_id""";

  private static final String SHAPE =
      """
      SELECT granularity, count(*), coalesce(max(scc_size), 0), coalesce(max(component_id), 0),
             count(betweenness), sum(fan_out = 0), sum(fan_in = 0)
        FROM graph_metric WHERE analysis_pk = ? GROUP BY granularity""";

  @TempDir private Path temp;

  @Test
  void oGrafoEstruturalNaoDependeDaOrdemDeChegadaSomaUmEPassaNoPontoDeDecisao() throws Exception {
    HermeticSystemReader.install();
    final double loadAtStart = loadAverage();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final Environment environment = environment();
    final String baseCommit = headOf(strataRoot).substring(0, 7);
    final JsonNode baseline = JSON.readTree(strataRoot.resolve(BASELINE).toFile());
    final Path corpus = corpusDirectory();
    final Path report = strataRoot.resolve(REPORT);
    final Path dumps = Files.createDirectories(strataRoot.resolve(DUMPS));
    final Measured measured =
        new Measured(baseCommit, settings(baseline, loadAtStart, null), environment);

    for (final int types : SYNTHETIC_SIZES) {
      final Synthetic graph =
          SyntheticGraphs.generate(types, types / TYPES_PER_PACKAGE, SYNTHETIC_SEED);
      measured.synthetic.add(synthetic(graph));
      if (types == TOLERANCE_SYNTHETIC_TYPES) {
        measured.tolerance.addAll(
            tolerance(
                "synthetic-" + types,
                StructuralGraphBuilder.build(graph.input(), PACKAGES, NOT_CANCELLED).forward()));
      }
      write(report, measured.report(null, null));
    }

    for (final String name : REPOSITORIES) {
      final Path repository = corpus.resolve(name).toRealPath();
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        final boolean largest = LARGEST.equals(name) && selection == Selection.ALL;
        final List<RunResult> runs = new ArrayList<>();
        final List<ReadBack> readBacks = new ArrayList<>();
        GraphSource source = null;
        for (int run = 1; run <= RUNS; run++) {
          final Analyzed analyzed =
              analyze(name, repository, selection, run, dumps, largest && run == RUNS);
          runs.add(analyzed.run());
          readBacks.add(analyzed.readBack());
          source = analyzed.source() == null ? source : analyzed.source();
        }
        selections.add(
            selectionResult(name, selection, runs, readBacks.getFirst(), baseline, dumps));
        if (source != null) {
          final GraphInput input = input(source);
          source = null;
          measured.corpusShuffle =
              corpusShuffle(
                  name, selection, input, strataRoot.resolve(runs.getLast().graphMetric().file()));
          measured.tolerance.addAll(
              tolerance(
                  name + "-" + selection.name(),
                  StructuralGraphBuilder.build(input, PACKAGES, NOT_CANCELLED).forward()));
        }
        measured.withRepository(name, repository, selections);
        write(report, measured.report(null, null));
      }
    }

    measured.settings = settings(baseline, loadAtStart, loadAverage());
    final List<Expectation> expectations = measured.expectations();
    final Decision decision = measured.decision();
    write(report, measured.report(decision, expectations));

    final SoftAssertions softly = new SoftAssertions();
    for (final Expectation expectation : expectations) {
      softly
          .assertThat(expectation.met())
          .as(
              "%s: esperado %s, medido %s",
              expectation.id(), expectation.expected(), expectation.measured())
          .isTrue();
    }
    softly.assertThat(decision.outcome()).as("a regra de decisão").isEqualTo("PASS");
    softly.assertAll();
  }

  private static SyntheticResult synthetic(Synthetic graph) throws InterruptedException {
    final GraphInput input = graph.input();
    final int types = input.types().size();
    final Timed baseline = timedCompute(input);
    final List<ShuffledOrder> orders = shuffledOrders(input, baseline);
    final int[] fanOut = new int[types];
    final int[] fanIn = new int[types];
    for (final long edge : graph.edges()) {
      fanOut[SyntheticGraphs.source(edge)]++;
      fanIn[SyntheticGraphs.target(edge)]++;
    }
    return new SyntheticResult(
        types,
        types / TYPES_PER_PACKAGE,
        SYNTHETIC_SEED,
        input.rows().size(),
        graph.edges().length,
        Arrays.stream(fanOut).filter(degree -> degree == 0).count(),
        Arrays.stream(fanIn).filter(degree -> degree == 0).count(),
        baseline.result().counters().asMap(),
        sums(baseline.result()),
        baseline.compute(),
        orders,
        control(graph, baseline.result()));
  }

  private static List<ShuffledOrder> shuffledOrders(GraphInput input, Timed baseline)
      throws InterruptedException {
    final List<ShuffledOrder> orders = new ArrayList<>();
    for (final long seed : SHUFFLE_SEEDS) {
      final GraphInput reordered =
          new GraphInput(
              shuffled(input.types(), seed), shuffled(input.rows(), seed + ROW_SEED_OFFSET));
      final Timed again = timedCompute(reordered);
      long differing = 0;
      for (final RankVector vector : RankVector.values()) {
        differing +=
            differingBits(vector.inIdOrder(baseline.result()), vector.inIdOrder(again.result()));
      }
      orders.add(
          new ShuffledOrder(
              seed,
              seed + ROW_SEED_OFFSET,
              differing,
              again.result().equals(baseline.result()),
              again.compute()));
    }
    return orders;
  }

  private static Control control(Synthetic graph, StructuralGraphResult result) {
    final int n = graph.input().types().size();
    final SplittableRandom random = new SplittableRandom(CONTROL_SEED);
    final Graph<Integer, DefaultEdge> sorted =
        jgraphtGraph(IntStream.range(0, n).toArray(), graph.edges());
    final Graph<Integer, DefaultEdge> unsorted =
        jgraphtGraph(permutation(n, random), shuffled(graph.edges(), random));
    return new Control(
        CONTROL_SEED,
        List.of(
            controlOrientation(
                Orientation.G,
                jgraphtPageRank(sorted),
                jgraphtPageRank(unsorted),
                RankVector.TYPE_PAGERANK.inIdOrder(result)),
            controlOrientation(
                Orientation.G_REV,
                jgraphtPageRank(new EdgeReversedGraph<>(sorted)),
                jgraphtPageRank(new EdgeReversedGraph<>(unsorted)),
                RankVector.TYPE_PAGERANK_OUT.inIdOrder(result))));
  }

  private static ControlOrientation controlOrientation(
      Orientation orientation, double[] sorted, double[] unsorted, double[] analyzer) {
    long differing = 0;
    long maxUlps = 0;
    double maxAbs = 0;
    double maxRelative = 0;
    int firstMovingDigit = SIGNIFICANT_DIGITS + 1;
    for (int vertex = 0; vertex < sorted.length; vertex++) {
      final long a = Double.doubleToRawLongBits(sorted[vertex]);
      final long b = Double.doubleToRawLongBits(unsorted[vertex]);
      if (a == b) {
        continue;
      }
      differing++;
      maxUlps = Math.max(maxUlps, Math.abs(a - b));
      final double difference = Math.abs(sorted[vertex] - unsorted[vertex]);
      maxAbs = Math.max(maxAbs, difference);
      maxRelative = Math.max(maxRelative, difference / sorted[vertex]);
      firstMovingDigit =
          Math.min(firstMovingDigit, firstMovingDigit(sorted[vertex], unsorted[vertex]));
    }
    final long[] ties = tieGroups(sorted, unsorted);
    return new ControlOrientation(
        orientation.label,
        Arrays.equals(rawBits(sorted), rawBits(analyzer)),
        differing,
        maxUlps,
        maxAbs,
        maxRelative,
        differing == 0 ? 0 : (int) Math.floor(-Math.log10(maxRelative)) + 1,
        differing == 0 ? 0 : firstMovingDigit,
        ties[0],
        ties[1]);
  }

  private static int firstMovingDigit(double a, double b) {
    final String x = significantDigits(a);
    final String y = significantDigits(b);
    for (int position = 0; position < x.length(); position++) {
      if (x.charAt(position) != y.charAt(position)) {
        return position + 1;
      }
    }
    return SIGNIFICANT_DIGITS + 1;
  }

  private static String significantDigits(double value) {
    final String formatted = String.format(Locale.ROOT, "%.16e", value);
    return formatted.charAt(0) + formatted.substring(2, SIGNIFICANT_DIGITS + 1);
  }

  private static long[] tieGroups(double[] sorted, double[] unsorted) {
    final Map<Long, List<Integer>> byBits = new HashMap<>();
    for (int vertex = 0; vertex < sorted.length; vertex++) {
      byBits
          .computeIfAbsent(Double.doubleToRawLongBits(sorted[vertex]), bits -> new ArrayList<>())
          .add(vertex);
    }
    long groups = 0;
    long split = 0;
    for (final List<Integer> members : byBits.values()) {
      if (members.size() < 2) {
        continue;
      }
      groups++;
      final long first = Double.doubleToRawLongBits(unsorted[members.getFirst()]);
      if (members.stream().anyMatch(m -> Double.doubleToRawLongBits(unsorted[m]) != first)) {
        split++;
      }
    }
    return new long[] {groups, split};
  }

  private static List<ToleranceResult> tolerance(String graphName, Graph<Integer, DefaultEdge> g) {
    final List<ToleranceResult> results = new ArrayList<>();
    for (final Orientation orientation : Orientation.values()) {
      final Graph<Integer, DefaultEdge> graph = orientation.of(g);
      final double[] reference = jgraphtPageRank(graph, REFERENCE_CAP, REFERENCE_TOLERANCE);
      final boolean referenceConverged =
          Arrays.equals(
              rawBits(reference),
              rawBits(jgraphtPageRank(graph, REFERENCE_CAP + 1, REFERENCE_TOLERANCE)));
      final long[] referenceTenths = percentileTenths(reference);
      final List<ToleranceRun> runs = new ArrayList<>();
      for (final double tolerance :
          new double[] {COARSE_TOLERANCE, AnalysisConstants.PAGERANK_TOLERANCE}) {
        runs.add(toleranceRun(graph, tolerance, reference, referenceTenths));
      }
      results.add(
          new ToleranceResult(
              graphName,
              orientation.label,
              graph.vertexSet().size(),
              graph.edgeSet().size(),
              REFERENCE_TOLERANCE,
              REFERENCE_CAP,
              referenceConverged,
              runs));
    }
    return results;
  }

  private static ToleranceRun toleranceRun(
      Graph<Integer, DefaultEdge> graph,
      double tolerance,
      double[] reference,
      long[] referenceTenths) {
    final int cap = AnalysisConstants.PAGERANK_MAX_ITER;
    final int iterations = iterationsToConverge(graph, tolerance);
    final boolean notOneBelow = iterations <= 1 || !converged(graph, tolerance, iterations - 1);
    final double[] vector = jgraphtPageRank(graph, cap, tolerance);
    final boolean production = tolerance == AnalysisConstants.PAGERANK_TOLERANCE;
    final Boolean detectorAgrees =
        production
            ? iterations >= 1
                && GraphAlgorithms.pageRank(graph, iterations).converged()
                && (iterations == 1 || !GraphAlgorithms.pageRank(graph, iterations - 1).converged())
            : null;
    final Boolean productionVector =
        production
            ? Arrays.equals(
                rawBits(vector), rawBits(GraphAlgorithms.pageRank(graph, cap).toArray()))
            : null;
    double maxAbs = 0;
    double maxRelative = 0;
    for (int vertex = 0; vertex < vector.length; vertex++) {
      final double error = Math.abs(vector[vertex] - reference[vertex]);
      maxAbs = Math.max(maxAbs, error);
      maxRelative = Math.max(maxRelative, error / reference[vertex]);
    }
    final long[] tenths = percentileTenths(vector);
    long changed = 0;
    long maxShift = 0;
    for (int vertex = 0; vertex < tenths.length; vertex++) {
      final long shift = Math.abs(tenths[vertex] - referenceTenths[vertex]);
      changed += shift == 0 ? 0 : 1;
      maxShift = Math.max(maxShift, shift);
    }
    return new ToleranceRun(
        tolerance,
        iterations,
        notOneBelow,
        detectorAgrees,
        productionVector,
        maxAbs,
        maxRelative,
        changed,
        maxShift / (double) TENTHS);
  }

  private static int iterationsToConverge(Graph<Integer, DefaultEdge> graph, double tolerance) {
    if (!converged(graph, tolerance, AnalysisConstants.PAGERANK_MAX_ITER)) {
      return -1;
    }
    int low = 1;
    int high = AnalysisConstants.PAGERANK_MAX_ITER;
    while (low < high) {
      final int middle = (low + high) >>> 1;
      if (converged(graph, tolerance, middle)) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    return low;
  }

  private static boolean converged(Graph<Integer, DefaultEdge> graph, double tolerance, int cap) {
    return Arrays.equals(
        rawBits(jgraphtPageRank(graph, cap, tolerance)),
        rawBits(jgraphtPageRank(graph, cap + 1, tolerance)));
  }

  private static long[] percentileTenths(double[] values) {
    final double[] sorted = values.clone();
    Arrays.sort(sorted);
    final long[] tenths = new long[values.length];
    for (int vertex = 0; vertex < values.length; vertex++) {
      final int below = lowerBound(sorted, values[vertex]);
      final int equal = lowerBound(sorted, Math.nextUp(values[vertex])) - below;
      final double percentile = PERCENT * (below + 0.5 * equal) / values.length;
      tenths[vertex] = Math.round(percentile * TENTHS);
    }
    return tenths;
  }

  private static int lowerBound(double[] sorted, double value) {
    int low = 0;
    int high = sorted.length;
    while (low < high) {
      final int middle = (low + high) >>> 1;
      if (sorted[middle] < value) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private Analyzed analyze(
      String name, Path repository, Selection selection, int run, Path dumps, boolean captureSource)
      throws Exception {
    final String label = name + "-" + selection.name() + "-run" + run;
    final Path data = temp.toRealPath().resolve(label);
    final RecordingFrameSink sink = new RecordingFrameSink();
    final RegistrySettings settings =
        RegistrySettings.production(PersistenceRoot.prepare(data), sink, ANALYZER_VERSION)
            .withPlan(PLAN);
    final Path metricDump = dumps.resolve(label + "-graph_metric.tsv");
    final Path edgeDump = dumps.resolve(label + "-package_edge.tsv");
    System.gc();
    final double loadStart = loadAverage();
    final AnalysisRegistry registry = new AnalysisRegistry(settings);
    final AnalysisId id;
    final long peakLive;
    final long peakUsed;
    final ReadBack readBack;
    GraphSource source = null;
    try {
      try (HeapPeakMeter meter = new HeapPeakMeter()) {
        id = RunFixtures.launch(registry, repository, selection.configuration());
        sink.awaitTerminal(id, RUN_TIMEOUT);
        meter.drain();
        peakLive = meter.peakLiveBytes();
        peakUsed = meter.peakUsedBytes();
      }
      RunFixtures.runThread(sink, id).join(RUN_TIMEOUT.toMillis());
      final ReadableAnalysis readable =
          registry
              .readable(id)
              .orElseThrow(() -> new AssertionError("a análise " + id + " não ficou legível"));
      final long pk = readable.analysisPk();
      readBack = readable.connections().read(c -> readBack(c, pk, metricDump, edgeDump));
      if (captureSource) {
        source = readable.connections().read(c -> GraphInputDao.read(c, pk, NOT_CANCELLED));
      }
    } finally {
      registry.close();
    }
    final double loadEnd = loadAverage();
    deleteTree(data);
    return new Analyzed(
        RunResult.of(
            run, id, sink.of(id), readBack, new Heap(peakLive, peakUsed), loadStart, loadEnd),
        readBack,
        source);
  }

  private static ReadBack readBack(Connection c, long pk, Path metricDump, Path edgeDump)
      throws SQLException {
    final Map<String, long[]> shape = shape(c, pk);
    return new ReadBack(
        stageRow(c, pk),
        dump(c, pk, GRAPH_METRIC_DUMP, metricDump, REAL_COLUMNS),
        dump(c, pk, PACKAGE_EDGE_DUMP, edgeDump, List.of()),
        List.of(vectorSums(c, pk, "TYPE"), vectorSums(c, pk, "PACKAGE")).stream()
            .flatMap(List::stream)
            .toList(),
        top(c, pk, "pagerank"),
        top(c, pk, "pagerank_out"),
        cyclesInOneTopLevelType(c, pk),
        Shape.of(shape.get("TYPE")),
        Shape.of(shape.get("PACKAGE")));
  }

  private static StageRow stageRow(Connection c, long pk) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(STAGE_ROW)) {
      statement.setLong(1, pk);
      statement.setString(2, STAGE);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        return new StageRow(result.getString(1), result.getLong(2), counters(result.getString(3)));
      }
    }
  }

  private static Dump dump(
      Connection c, long pk, String sql, Path target, List<Integer> realColumns)
      throws SQLException {
    long rows = 0;
    try (PreparedStatement statement = c.prepareStatement(sql);
        BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      statement.setLong(1, pk);
      try (ResultSet result = statement.executeQuery()) {
        final int columns = result.getMetaData().getColumnCount();
        while (result.next()) {
          final StringBuilder line = new StringBuilder();
          for (int column = 1; column <= columns; column++) {
            line.append(column == 1 ? "" : "\t").append(cell(result, column, realColumns));
          }
          out.write(line.append('\n').toString());
          rows++;
        }
      }
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
    try {
      final byte[] bytes = Files.readAllBytes(target);
      return new Dump(DUMPS + "/" + target.getFileName(), rows, sha256(bytes));
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  private static String cell(ResultSet result, int column, List<Integer> realColumns)
      throws SQLException {
    if (result.getObject(column) == null) {
      return "NULL";
    }
    return realColumns.contains(column)
        ? HEX.toHexDigits(Double.doubleToRawLongBits(result.getDouble(column)))
        : result.getString(column);
  }

  private static List<VectorSum> vectorSums(Connection c, long pk, String granularity)
      throws SQLException {
    final List<Double> pagerank = new ArrayList<>();
    final List<Double> pagerankOut = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(VECTORS)) {
      statement.setLong(1, pk);
      statement.setString(2, granularity);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          pagerank.add(result.getDouble(1));
          pagerankOut.add(result.getDouble(2));
        }
      }
    }
    return List.of(
        VectorSum.of(granularity + " pagerank", toArray(pagerank)),
        VectorSum.of(granularity + " pagerankOut", toArray(pagerankOut)));
  }

  private static List<Ranked> top(Connection c, long pk, String column) throws SQLException {
    final List<Ranked> top = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(TOP_BY.formatted(column, column, TOP))) {
      statement.setLong(1, pk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          top.add(
              new Ranked(
                  result.getString(1), result.getDouble(2), result.getLong(3), result.getLong(4)));
        }
      }
    }
    return List.copyOf(top);
  }

  private static long cyclesInOneTopLevelType(Connection c, long pk) throws SQLException {
    final Map<Long, List<String>> topLevels = new LinkedHashMap<>();
    try (PreparedStatement statement = c.prepareStatement(CYCLE_MEMBERS)) {
      statement.setLong(1, pk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          if (!"PACKAGE".equals(result.getString(4))) {
            throw new IllegalStateException("um tipo cujo pai não é um pacote");
          }
          topLevels
              .computeIfAbsent(result.getLong(1), scc -> new ArrayList<>())
              .add(topLevelType(result.getString(2), result.getString(3)));
        }
      }
    }
    return topLevels.values().stream()
        .filter(members -> members.stream().distinct().count() == 1)
        .count();
  }

  private static String topLevelType(String typeName, String packageName) {
    final String prefix = packageName.isEmpty() ? "" : packageName + ".";
    if (!typeName.startsWith(prefix)) {
      throw new IllegalStateException(typeName + " não está dentro de " + packageName);
    }
    final String rest = typeName.substring(prefix.length());
    final int dot = rest.indexOf('.');
    return prefix + (dot < 0 ? rest : rest.substring(0, dot));
  }

  private static Map<String, long[]> shape(Connection c, long pk) throws SQLException {
    final Map<String, long[]> shape = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(SHAPE)) {
      statement.setLong(1, pk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          final long[] values = new long[6];
          for (int column = 0; column < values.length; column++) {
            values[column] = result.getLong(column + 2);
          }
          shape.put(result.getString(1), values);
        }
      }
    }
    return shape;
  }

  private static GraphInput input(GraphSource source) {
    return new GraphInput(
        source.types().stream().map(type -> new TypeNode(type.id(), type.packageId())).toList(),
        source.rows());
  }

  private static CorpusShuffle corpusShuffle(
      String name, Selection selection, GraphInput input, Path graphMetricDump)
      throws IOException, InterruptedException {
    final Timed baseline = timedCompute(input);
    final Map<String, String[]> persisted = new HashMap<>();
    for (final String line : Files.readAllLines(graphMetricDump, StandardCharsets.UTF_8)) {
      final String[] fields = line.split("\t", -1);
      persisted.put(fields[0], fields);
    }
    long mismatches = 0;
    for (final TypeMetrics type : baseline.result().types()) {
      mismatches +=
          mismatches(persisted.get(type.id().value()), type.pagerank(), type.pagerankOut());
    }
    for (final PackageMetrics pkg : baseline.result().packages()) {
      mismatches += mismatches(persisted.get(pkg.id().value()), pkg.pagerank(), pkg.pagerankOut());
    }
    return new CorpusShuffle(
        name,
        selection.name(),
        input.types().size(),
        input.rows().size(),
        baseline.compute(),
        baseline.result().types().size() + baseline.result().packages().size(),
        mismatches,
        shuffledOrders(input, baseline));
  }

  private static long mismatches(String[] row, double pagerank, double pagerankOut) {
    if (row == null) {
      return 2;
    }
    return (row[PAGERANK_FIELD].equals(HEX.toHexDigits(Double.doubleToRawLongBits(pagerank)))
            ? 0
            : 1)
        + (row[PAGERANK_OUT_FIELD].equals(HEX.toHexDigits(Double.doubleToRawLongBits(pagerankOut)))
            ? 0
            : 1);
  }

  private static SelectionResult selectionResult(
      String name,
      Selection selection,
      List<RunResult> runs,
      ReadBack readBack,
      JsonNode baseline,
      Path dumps)
      throws IOException {
    final RunResult first = runs.getFirst();
    final RunResult second = runs.getLast();
    final List<TableDeterminism> tables =
        List.of(
            TableDeterminism.of("graph_metric", first.graphMetric(), second.graphMetric(), dumps),
            TableDeterminism.of("package_edge", first.packageEdge(), second.packageEdge(), dumps));
    final boolean countersEqual = first.counters().equals(second.counters());
    return new SelectionResult(
        selection.name(),
        baselineTypes(baseline, name, selection),
        new Determinism(
            tables,
            countersEqual,
            countersEqual && tables.stream().allMatch(TableDeterminism::identical)),
        readBack.typeShape(),
        readBack.packageShape(),
        readBack.cyclesInOneTopLevelType(),
        readBack.topPagerank(),
        readBack.topPagerankOut(),
        runs);
  }

  private static final class Measured {

    private final String baseCommit;
    private final Environment environment;
    private final List<SyntheticResult> synthetic = new ArrayList<>();
    private final List<ToleranceResult> tolerance = new ArrayList<>();
    private final List<RepositoryResult> repositories = new ArrayList<>();
    private Settings settings;
    private CorpusShuffle corpusShuffle;

    Measured(String baseCommit, Settings settings, Environment environment) {
      this.baseCommit = baseCommit;
      this.settings = settings;
      this.environment = environment;
    }

    void withRepository(String name, Path repository, List<SelectionResult> selections)
        throws IOException {
      repositories.removeIf(result -> result.name().equals(name));
      repositories.add(
          new RepositoryResult(
              name,
              DERIVED.equals(name) ? "derived" : "clone",
              headOf(repository),
              List.copyOf(selections)));
    }

    Report report(Decision decision, List<Expectation> expectations) {
      return new Report(
          SCHEMA,
          Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
          baseCommit,
          MEASURE,
          settings,
          environment,
          decision,
          expectations,
          List.copyOf(synthetic),
          List.copyOf(tolerance),
          corpusShuffle,
          List.copyOf(repositories));
    }

    Decision decision() {
      final List<String> evidence = new ArrayList<>();
      boolean pass = true;
      for (final SyntheticResult graph : synthetic) {
        final boolean identical = graph.shuffledOrders().stream().allMatch(ShuffledOrder::same);
        final boolean sums = graph.sums().stream().allMatch(VectorSum::withinTolerance);
        final boolean dangling = graph.sinks() > 0 && graph.neverTargeted() > 0;
        pass &= identical && sums && dangling;
        evidence.add(
            "synthetic %d types: %d/%d shuffled orders bit-identical; max |sum-1| %s over 4 vectors; dangling %d in G, %d in G_rev"
                .formatted(
                    graph.types(),
                    graph.shuffledOrders().stream().filter(ShuffledOrder::same).count(),
                    graph.shuffledOrders().size(),
                    maxError(graph.sums()),
                    graph.sinks(),
                    graph.neverTargeted()));
      }
      final boolean corpusOrders =
          corpusShuffle != null
              && corpusShuffle.persistedMismatches() == 0
              && corpusShuffle.shuffledOrders().stream().allMatch(ShuffledOrder::same);
      pass &= corpusOrders;
      evidence.add(
          corpusShuffle == null
              ? "elasticsearch ALL shuffle: not measured"
              : "elasticsearch ALL read back from the cache: %d/%d shuffled orders bit-identical, %d doubles differ from the persisted rows"
                  .formatted(
                      corpusShuffle.shuffledOrders().stream().filter(ShuffledOrder::same).count(),
                      corpusShuffle.shuffledOrders().size(),
                      corpusShuffle.persistedMismatches()));
      for (final RepositoryResult repository : repositories) {
        for (final SelectionResult selection : repository.selections()) {
          final boolean sums =
              selection.runs().stream()
                  .allMatch(run -> run.sums().stream().allMatch(VectorSum::withinTolerance));
          final boolean dangling =
              selection.typeShape().sinks() > 0 && selection.typeShape().neverTargeted() > 0;
          pass &= selection.determinism().identical() && sums && dangling;
          evidence.add(
              "%s %s: two analyses %s; max |sum-1| %s over 4 vectors x 2 runs; dangling %d in G, %d in G_rev"
                  .formatted(
                      repository.name(),
                      selection.selection(),
                      selection.determinism().identical() ? "byte-identical" : "DIFFER",
                      maxError(
                          selection.runs().stream().flatMap(run -> run.sums().stream()).toList()),
                      selection.typeShape().sinks(),
                      selection.typeShape().neverTargeted()));
        }
      }
      return new Decision(
          "PASS iff two builds of the graph from the same rows in different arrival orders produce"
              + " bit-identical doubles (five shuffled orders of every synthetic graph and of"
              + " Elasticsearch ALL; two analyses of every corpus selection) and |sum - 1| <= 1e-9"
              + " for every PageRank vector of every graph, each containing dangling nodes in G"
              + " and in G_rev; else FAIL",
          pass ? "PASS" : "FAIL",
          List.copyOf(evidence));
    }

    List<Expectation> expectations() {
      final Checks checks = new Checks();
      for (final SyntheticResult graph : synthetic) {
        syntheticChecks(checks, graph);
      }
      for (final ToleranceResult result : tolerance) {
        toleranceChecks(checks, result);
      }
      if (corpusShuffle == null) {
        checks.that("corpus shuffle", "measured", "absent", false);
      } else {
        checks.equal(
            "elasticsearch ALL compute == persisted bits", 0L, corpusShuffle.persistedMismatches());
        checks.equal(
            "elasticsearch ALL shuffled orders bit-identical",
            (long) SHUFFLE_SEEDS.length,
            corpusShuffle.shuffledOrders().stream().filter(ShuffledOrder::same).count());
      }
      for (final RepositoryResult repository : repositories) {
        for (final SelectionResult selection : repository.selections()) {
          corpusChecks(checks, repository.name(), selection);
        }
      }
      return checks.list();
    }
  }

  private static void syntheticChecks(Checks checks, SyntheticResult graph) {
    final String at = "synthetic " + graph.types() + ": ";
    checks.equal(
        at + "shuffled orders bit-identical",
        (long) SHUFFLE_SEEDS.length,
        graph.shuffledOrders().stream().filter(ShuffledOrder::same).count());
    checks.that(
        at + "|sum-1| <= 1e-9",
        "every vector",
        maxError(graph.sums()),
        graph.sums().stream().allMatch(VectorSum::withinTolerance));
    checks.equal(at + "pagerankUnconverged", 0L, graph.counters().get("pagerankUnconverged"));
    for (final ControlOrientation orientation : graph.control().orientations()) {
      final String side = at + "control " + orientation.orientation() + ": ";
      checks.that(side + "differs", "> 0", orientation.differing(), orientation.differing() > 0);
      checks.that(
          side + "max |difference| < 1e-12",
          "< 1e-12",
          orientation.maxAbsDifference(),
          orientation.maxAbsDifference() < ROUNDING_ONLY);
      checks.equal(side + "sorted JGraphT == analyzer", true, orientation.sortedEqualsAnalyzer());
    }
    if (graph.types() == CONTROL_TYPES) {
      final List<ControlOrientation> sides = graph.control().orientations();
      checks.equal(
          at + "control G differing (PageRankTest)", CONTROL_DIFFERING_G, sides.get(0).differing());
      checks.equal(
          at + "control G_rev differing (PageRankTest)",
          CONTROL_DIFFERING_G_REV,
          sides.get(1).differing());
      checks.equal(
          at + "control max ulps (PageRankTest)",
          CONTROL_MAX_ULPS,
          Math.max(sides.get(0).maxUlps(), sides.get(1).maxUlps()));
    }
  }

  private static void toleranceChecks(Checks checks, ToleranceResult result) {
    final String at = "tolerance " + result.graph() + " " + result.orientation() + ": ";
    final ToleranceRun coarse = result.runs().get(0);
    final ToleranceRun production = result.runs().get(1);
    final boolean largest = result.graph().startsWith(LARGEST);
    checks.equal(at + "reference converged", true, result.referenceConverged());
    checks.that(
        at + "1e-10 percentiles changed <= 2 and < 1e-6's",
        "<= " + MAX_PERCENTILES_CHANGED_AT_PRODUCTION + " and < " + coarse.percentilesChanged(),
        production.percentilesChanged(),
        production.percentilesChanged() <= MAX_PERCENTILES_CHANGED_AT_PRODUCTION
            && production.percentilesChanged() < coarse.percentilesChanged());
    checks.that(
        at + "1e-10 iterations <= 147 and > 1e-6's",
        "(" + coarse.iterations() + ", 147]",
        production.iterations(),
        production.iterations() > coarse.iterations()
            && production.iterations() <= CONTRACTION_BOUND_ITERATIONS);
    checks.equal(at + "detector agrees at k and k-1", true, production.detectorAgrees());
    checks.equal(
        at + "1e-10 vector == GraphAlgorithms.pageRank", true, production.productionVector());
    checks.equal(at + "1e-6 bisection boundary", true, coarse.notConvergedOneBelow());
    checks.equal(at + "1e-10 bisection boundary", true, production.notConvergedOneBelow());
    if (largest) {
      checks.equal(
          at + "1e-6 iterations (recorded)",
          Orientation.G.label.equals(result.orientation())
              ? ES_COARSE_ITERATIONS_G
              : ES_COARSE_ITERATIONS_G_REV,
          coarse.iterations());
    } else {
      checks.that(
          at + "1e-6 percentiles changed",
          "> 0",
          coarse.percentilesChanged(),
          coarse.percentilesChanged() > 0);
    }
  }

  private static void corpusChecks(Checks checks, String name, SelectionResult selection) {
    final String at = name + " " + selection.selection() + ": ";
    final Census census = CENSUS.get(name + "/" + selection.selection());
    final RunResult first = selection.runs().getFirst();
    final Map<String, Long> counters = first.counters();
    for (final RunResult run : selection.runs()) {
      final String which = at + "run " + run.run() + " ";
      checks.equal(which + "terminal event", ANALYSIS_COMPLETED, run.terminalEvent());
      checks.equal(which + "STRUCTURAL_GRAPH status", COMPLETED, run.status());
      checks.equal(which + "analysis_stage row == event", true, run.stageRowMatchesEvent());
      checks.that(
          which + "|sum-1| <= 1e-9",
          "every vector",
          maxError(run.sums()),
          run.sums().stream().allMatch(VectorSum::withinTolerance));
    }
    checks.equal(at + "dumps byte-identical", true, selection.determinism().identical());
    checks.equal(
        at + "typeNodes == stages-baseline types",
        selection.baselineTypes(),
        counters.get("typeNodes"));
    checks.equal(at + "typeNodes", census.typeNodes(), counters.get("typeNodes"));
    checks.equal(at + "typeEdges", census.typeEdges(), counters.get("typeEdges"));
    checks.equal(at + "packageNodes", census.packageNodes(), counters.get("packageNodes"));
    checks.equal(at + "packageEdges", census.packageEdges(), counters.get("packageEdges"));
    checks.equal(at + "typeCycles", census.typeCycles(), counters.get("typeCycles"));
    checks.equal(
        at + "largest type cycle", census.largestTypeCycle(), selection.typeShape().largestCycle());
    checks.equal(at + "typesInCycles", census.typesInCycles(), counters.get("typesInCycles"));
    checks.equal(at + "packageCycles", census.packageCycles(), counters.get("packageCycles"));
    checks.equal(
        at + "largest package cycle",
        census.largestPackageCycle(),
        selection.packageShape().largestCycle());
    checks.equal(
        at + "packagesInCycles", census.packagesInCycles(), counters.get("packagesInCycles"));
    checks.equal(at + "externalPairs", census.externalPairs(), counters.get("externalPairs"));
    checks.equal(at + "denylistedPairs", census.denylistedPairs(), counters.get("denylistedPairs"));
    checks.equal(
        at + "weak components", census.weakComponents(), selection.packageShape().components());
    checks.equal(
        at + "cycles in one top-level type",
        census.cyclesInOneTopLevelType(),
        selection.cyclesInOneTopLevelType());
    checks.equal(at + "pagerankUnconverged", 0L, counters.get("pagerankUnconverged"));
    checks.equal(
        at + "betweennessSkipped",
        LARGEST.equals(name) ? 1L : 0L,
        counters.get("betweennessSkipped"));
    final Ranked topPagerank = selection.topPagerank().getFirst();
    final Ranked topPagerankOut = selection.topPagerankOut().getFirst();
    checks.equal(at + "top pagerank (recorded G)", census.topPagerank(), topPagerank.id());
    checks.that(
        at + "top pagerank fanIn >= recorded p99",
        ">= " + census.fanInP99(),
        topPagerank.fanIn(),
        topPagerank.fanIn() >= census.fanInP99());
    checks.equal(
        at + "top pagerankOut (recorded G_rev)", census.topPagerankOut(), topPagerankOut.id());
  }

  private static double maxError(List<VectorSum> sums) {
    return sums.stream()
        .mapToDouble(sum -> Math.max(Math.abs(sum.sumMinusOne()), Math.abs(sum.exactSumMinusOne())))
        .max()
        .orElse(Double.NaN);
  }

  private static final class Checks {

    private final List<Expectation> list = new ArrayList<>();

    void equal(String id, Object expected, Object measured) {
      list.add(
          new Expectation(
              id,
              String.valueOf(expected),
              String.valueOf(measured),
              Objects.equals(expected, measured)));
    }

    void that(String id, String expected, Object measured, boolean met) {
      list.add(new Expectation(id, expected, String.valueOf(measured), met));
    }

    List<Expectation> list() {
      return List.copyOf(list);
    }
  }

  private static Timed timedCompute(GraphInput input) throws InterruptedException {
    System.gc();
    try (HeapPeakMeter meter = new HeapPeakMeter()) {
      final long started = System.nanoTime();
      final StructuralGraphResult result =
          StructuralGraphAnalysis.compute(input, PACKAGES, NOT_CANCELLED);
      final long wallMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
      meter.drain();
      return new Timed(
          result, new Compute(wallMillis, meter.peakLiveBytes(), meter.peakUsedBytes()));
    }
  }

  private static List<VectorSum> sums(StructuralGraphResult result) {
    return Arrays.stream(RankVector.values())
        .map(vector -> VectorSum.of(vector.name(), vector.inIdOrder(result)))
        .toList();
  }

  private static long differingBits(double[] a, double[] b) {
    if (a.length != b.length) {
      return Math.max(a.length, b.length);
    }
    long differing = 0;
    for (int i = 0; i < a.length; i++) {
      differing += Double.doubleToRawLongBits(a[i]) == Double.doubleToRawLongBits(b[i]) ? 0 : 1;
    }
    return differing;
  }

  private static double[] toArray(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private static Map<String, Long> counters(String json) {
    try {
      return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Long>>() {});
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  private static long baselineTypes(JsonNode baseline, String name, Selection selection) {
    for (final JsonNode repository : baseline.path("repositories")) {
      if (!name.equals(repository.path("name").asText())) {
        continue;
      }
      for (final JsonNode configuration : repository.path("configurations")) {
        if (!selection.name().equals(configuration.path("selection").asText())) {
          continue;
        }
        for (final JsonNode stage : configuration.path("stages")) {
          if ("SOURCE_ANALYSIS".equals(stage.path("stage").asText())) {
            return stage.path("counters").path("types").asLong();
          }
        }
      }
    }
    throw new IllegalStateException(BASELINE + " não tem " + name + " " + selection);
  }

  private static Settings settings(JsonNode baseline, double loadAtStart, Double loadAtEnd) {
    final Map<String, Number> constants = new LinkedHashMap<>();
    constants.put("PAGERANK_DAMPING", AnalysisConstants.PAGERANK_DAMPING);
    constants.put("PAGERANK_TOLERANCE", AnalysisConstants.PAGERANK_TOLERANCE);
    constants.put("PAGERANK_MAX_ITER", AnalysisConstants.PAGERANK_MAX_ITER);
    constants.put("BETWEENNESS_MAX_NODES", AnalysisConstants.BETWEENNESS_MAX_NODES);
    constants.put("BETWEENNESS_MAX_EDGES", AnalysisConstants.BETWEENNESS_MAX_EDGES);
    final Map<String, SelectionPayload> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.payload());
    }
    return new Settings(
        jgraphtJar(),
        constants,
        PLAN_TEXT,
        selections,
        new SyntheticSettings(
            "SyntheticGraphs.generate(types, types / 75, 30): PageRankTest's generator"
                + ": log-normal out-degree (sigma 0.9, mean 6), 15% of"
                + " types depend on no internal type, 30% are never a target, Zipf(1) targets"
                + " over a permutation; each pair becomes 1-3 rows of distinct DependencyTypes,"
                + " half the types get one external row and one in twenty a self row",
            SYNTHETIC_SEED,
            TYPES_PER_PACKAGE,
            Arrays.stream(SHUFFLE_SEEDS).boxed().toList(),
            ROW_SEED_OFFSET,
            CONTROL_SEED),
        new ToleranceSettings(
            List.of(COARSE_TOLERANCE, AnalysisConstants.PAGERANK_TOLERANCE),
            REFERENCE_TOLERANCE,
            REFERENCE_CAP,
            "two-run recipe at the given tolerance; the smallest converged cap in"
                + " [1, PAGERANK_MAX_ITER] by bisection",
            "mid-rank percentile of each vertex within its own vector,"
                + " Math.round(10 x pct) compared against the reference's"),
        new Baseline(
            BASELINE, baseline.path("measuredAt").asText(), baseline.path("baseCommit").asText()),
        "values from an earlier corpus analysis (2026-10-23), embedded as literals in the IT",
        "scripts/corpus.sh's pinned clones, read-only, through -Dstrata.corpus",
        new LoadAverage(loadAtStart, loadAtEnd));
  }

  private static String jgraphtJar() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(entry -> entry.substring(entry.lastIndexOf(File.separatorChar) + 1))
        .filter(jar -> jar.startsWith("jgrapht-core-"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("jgrapht-core não está no classpath"));
  }

  private static double loadAverage() {
    return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
  }

  private static Environment environment() {
    final String cpu = System.getProperty("strata.host.cpu");
    final String storage = System.getProperty("strata.host.storage");
    assertThat(cpu)
        .as("o descritor de máquina é obrigatório: passe -Dstrata.host.cpu")
        .isNotBlank();
    assertThat(storage)
        .as("o descritor de máquina é obrigatório: passe -Dstrata.host.storage")
        .isNotBlank();
    final OperatingSystemMXBean system =
        ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    final List<String> flags =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(
                argument -> argument.startsWith("-X") || argument.startsWith("-Djava.awt.headless"))
            .toList();
    final String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
    final String jvm =
        System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version");
    final int cores = Runtime.getRuntime().availableProcessors();
    return new Environment(
        os,
        System.getProperty("os.arch"),
        cpu,
        cores,
        system.getTotalMemorySize(),
        storage,
        "full JDK under Maven Surefire, not the jlink image the application ships",
        jvm,
        flags,
        Runtime.getRuntime().maxMemory(),
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .toList(),
        assertsRun(),
        new Host(
            os,
            System.getProperty("os.arch"),
            cpu,
            cores,
            system.getTotalMemorySize(),
            storage,
            jvm));
  }

  private static boolean assertsRun() {
    boolean enabled = false;
    assert enabled = true;
    return enabled;
  }

  private static Path corpusDirectory() throws IOException {
    final Path corpus = Path.of(System.getProperty("strata.corpus"));
    assertThat(corpus.resolve(DERIVED))
        .as("o corpus não foi clonado: rode bash scripts/corpus.sh antes do spike")
        .isDirectory();
    return corpus.toRealPath();
  }

  private static String headOf(Path checkout) throws IOException {
    final File root = checkout.toFile();
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder().addCeilingDirectory(root.getParentFile()).findGitDir(root);
    assertThat(builder.getGitDir()).as("não é um checkout Git: " + root).isNotNull();
    try (Repository repository = builder.setMustExist(true).build()) {
      return Optional.ofNullable(repository.resolve("HEAD"))
          .map(head -> head.name())
          .orElse("unborn");
    }
  }

  private static String key(String name, Selection selection) {
    return name + "/" + selection.name();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void write(Path report, Report content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private enum Selection {
    DEFAULT(false),
    ALL(true);

    private final boolean includeTests;

    Selection(boolean includeTests) {
      this.includeTests = includeTests;
    }

    AnalysisConfiguration configuration() {
      return AnalysisConfiguration.normalized(includeTests, HistoryScope.ALL, null, null, false);
    }

    SelectionPayload payload() {
      return new SelectionPayload(includeTests, HistoryScope.ALL.name(), null, null);
    }
  }

  private enum Orientation {
    G("G"),
    G_REV("G_rev");

    private final String label;

    Orientation(String label) {
      this.label = label;
    }

    Graph<Integer, DefaultEdge> of(Graph<Integer, DefaultEdge> forward) {
      return this == G ? forward : new EdgeReversedGraph<>(forward);
    }
  }

  private record Census(
      long typeNodes,
      long typeEdges,
      long packageNodes,
      long packageEdges,
      long typeCycles,
      long largestTypeCycle,
      long typesInCycles,
      long packageCycles,
      long largestPackageCycle,
      long packagesInCycles,
      long externalPairs,
      long denylistedPairs,
      long weakComponents,
      long cyclesInOneTopLevelType,
      long fanInP99,
      String topPagerank,
      String topPagerankOut) {}

  record Report(
      int schema,
      String measuredAt,
      String baseCommit,
      String measure,
      Settings settings,
      Environment environment,
      Decision decision,
      List<Expectation> expectations,
      List<SyntheticResult> synthetic,
      List<ToleranceResult> tolerance,
      CorpusShuffle corpusShuffle,
      List<RepositoryResult> repositories) {}

  record Settings(
      String jgraphtJar,
      Map<String, Number> constants,
      String plan,
      Map<String, SelectionPayload> selections,
      SyntheticSettings synthetic,
      ToleranceSettings tolerance,
      Baseline baseline,
      String census,
      String corpus,
      LoadAverage loadAverage1m) {}

  record SyntheticSettings(
      String generator,
      long seed,
      int typesPerPackage,
      List<Long> shuffleSeeds,
      long rowSeedOffset,
      long controlSeed) {}

  record ToleranceSettings(
      List<Double> compared,
      double referenceTolerance,
      int referenceCap,
      String iterations,
      String percentiles) {}

  record LoadAverage(double start, Double end) {}

  record Decision(String rule, String outcome, List<String> evidence) {}

  record Expectation(String id, String expected, String measured, boolean met) {}

  record Compute(long wallMillis, long peakLiveBytes, long peakUsedBytes) {}

  private record Timed(StructuralGraphResult result, Compute compute) {}

  record VectorSum(String vector, int size, double sumMinusOne, double exactSumMinusOne) {

    static VectorSum of(String vector, double[] values) {
      double sum = 0;
      BigDecimal exact = BigDecimal.ZERO;
      for (final double value : values) {
        sum += value;
        exact = exact.add(new BigDecimal(value));
      }
      return new VectorSum(
          vector, values.length, sum - 1, exact.subtract(BigDecimal.ONE).doubleValue());
    }

    boolean withinTolerance() {
      return Math.abs(sumMinusOne) <= SUM_TOLERANCE && Math.abs(exactSumMinusOne) <= SUM_TOLERANCE;
    }
  }

  record ShuffledOrder(
      long typeSeed, long rowSeed, long differingDoubles, boolean equalResult, Compute compute) {

    boolean same() {
      return differingDoubles == 0 && equalResult;
    }
  }

  record ControlOrientation(
      String orientation,
      boolean sortedEqualsAnalyzer,
      long differing,
      long maxUlps,
      double maxAbsDifference,
      double maxRelativeDifference,
      int firstMovingSignificantDigit,
      int firstDifferingPrintedDigit,
      long tieGroups,
      long tieGroupsSplit) {}

  record Control(long seed, List<ControlOrientation> orientations) {}

  record SyntheticResult(
      int types,
      int packages,
      long seed,
      int rows,
      int typeEdges,
      long sinks,
      long neverTargeted,
      Map<String, Long> counters,
      List<VectorSum> sums,
      Compute compute,
      List<ShuffledOrder> shuffledOrders,
      Control control) {}

  record ToleranceRun(
      double tolerance,
      int iterations,
      boolean notConvergedOneBelow,
      Boolean detectorAgrees,
      Boolean productionVector,
      double maxAbsError,
      double maxRelativeError,
      long percentilesChanged,
      double maxPercentileShift) {}

  record ToleranceResult(
      String graph,
      String orientation,
      int vertices,
      int edges,
      double referenceTolerance,
      int referenceCap,
      boolean referenceConverged,
      List<ToleranceRun> runs) {}

  record CorpusShuffle(
      String repository,
      String selection,
      int types,
      int rows,
      Compute compute,
      long rowsCompared,
      long persistedMismatches,
      List<ShuffledOrder> shuffledOrders) {}

  record Heap(long peakLiveBytes, long peakUsedBytes) {}

  record Dump(String file, long rows, String sha256) {}

  record Ranked(String id, double value, long fanIn, long fanOut) {}

  record Shape(
      long rows,
      long largestCycle,
      long components,
      long betweennessRows,
      long sinks,
      long neverTargeted) {

    static Shape of(long[] values) {
      return values == null
          ? new Shape(0, 0, 0, 0, 0, 0)
          : new Shape(values[0], values[1], values[2], values[3], values[4], values[5]);
    }
  }

  private record StageRow(String status, long durationMs, Map<String, Long> counters) {}

  private record ReadBack(
      StageRow stageRow,
      Dump graphMetric,
      Dump packageEdge,
      List<VectorSum> sums,
      List<Ranked> topPagerank,
      List<Ranked> topPagerankOut,
      long cyclesInOneTopLevelType,
      Shape typeShape,
      Shape packageShape) {}

  private record Analyzed(RunResult run, ReadBack readBack, GraphSource source) {}

  record RunResult(
      int run,
      String analysisId,
      String terminalEvent,
      String status,
      Map<String, Long> counters,
      long durationMs,
      boolean stageRowMatchesEvent,
      long analysisWallMillis,
      Heap heap,
      LoadAverage loadAverage1m,
      Dump graphMetric,
      Dump packageEdge,
      List<VectorSum> sums) {

    static RunResult of(
        int run,
        AnalysisId id,
        List<Frame> frames,
        ReadBack readBack,
        Heap heap,
        double loadStart,
        double loadEnd) {
      final Frame started =
          frames.stream()
              .filter(frame -> "analysis.started".equals(frame.event()))
              .findFirst()
              .orElseThrow(() -> new AssertionError(id + " não tem analysis.started"));
      final Frame terminal =
          frames.stream()
              .filter(Frame::isTerminal)
              .findFirst()
              .orElseThrow(() -> new AssertionError(id + " não tem evento terminal"));
      final Frame stage =
          frames.stream()
              .filter(
                  frame ->
                      "analysis.stage.completed".equals(frame.event())
                          && STAGE.equals(frame.stage()))
              .findFirst()
              .orElseThrow(() -> new AssertionError(id + " não tem o stage.completed de " + STAGE));
      final String status = stage.payload().path("status").asText();
      final long durationMs = stage.payload().path("durationMs").asLong();
      final Map<String, Long> counters =
          JSON.convertValue(
              stage.payload().path("counters"),
              new TypeReference<LinkedHashMap<String, Long>>() {});
      final StageRow row = readBack.stageRow();
      return new RunResult(
          run,
          id.value(),
          terminal.event(),
          status,
          counters,
          durationMs,
          row != null
              && row.status().equals(status)
              && row.durationMs() == durationMs
              && row.counters().equals(counters),
          (terminal.nanos() - started.nanos()) / NANOS_PER_MILLI,
          heap,
          new LoadAverage(loadStart, loadEnd),
          readBack.graphMetric(),
          readBack.packageEdge(),
          readBack.sums());
    }
  }

  record TableDeterminism(
      String table,
      String firstDump,
      String secondDump,
      long rows,
      String firstSha256,
      String secondSha256,
      boolean identical) {

    static TableDeterminism of(String table, Dump first, Dump second, Path dumps)
        throws IOException {
      final long mismatch =
          Files.mismatch(
              dumps.resolve(Path.of(first.file()).getFileName()),
              dumps.resolve(Path.of(second.file()).getFileName()));
      return new TableDeterminism(
          table,
          first.file(),
          second.file(),
          first.rows(),
          first.sha256(),
          second.sha256(),
          mismatch == -1);
    }
  }

  record Determinism(List<TableDeterminism> tables, boolean countersEqual, boolean identical) {}

  record SelectionResult(
      String selection,
      long baselineTypes,
      Determinism determinism,
      Shape typeShape,
      Shape packageShape,
      long cyclesInOneTopLevelType,
      List<Ranked> topPagerank,
      List<Ranked> topPagerankOut,
      List<RunResult> runs) {}

  record RepositoryResult(
      String name, String origin, String commitId, List<SelectionResult> selections) {}
}
