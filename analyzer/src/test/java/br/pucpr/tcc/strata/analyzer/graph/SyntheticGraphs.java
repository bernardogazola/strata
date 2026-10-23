package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jgrapht.Graph;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

public final class SyntheticGraphs {

  static final double SINK_SHARE = 0.15;

  static final double NEVER_TARGETED_SHARE = 0.30;

  private static final double MEAN_OUT_DEGREE = 6;
  private static final double OUT_DEGREE_SIGMA = 0.9;
  private static final int TARGET_DRAW_ATTEMPTS_PER_EDGE = 50;

  private static final List<DependencyType> TYPE_EDGES =
      Arrays.stream(DependencyType.values()).filter(DependencyType::isTypeEdge).toList();

  private static final List<ExternalTarget> EXTERNAL_TARGETS =
      List.of(
          new ExternalTarget(CodeElementId.ofType("java.lang.String"), RESOLVED),
          new ExternalTarget(CodeElementId.ofType("java.util.List"), RESOLVED),
          new ExternalTarget(CodeElementId.ofType("java.util.concurrent.Callable"), RESOLVED),
          new ExternalTarget(CodeElementId.ofType("org.lib.Missing"), UNRESOLVED_EXTERNAL),
          new ExternalTarget(CodeElementId.ofType("Missing"), UNRESOLVED_EXTERNAL));

  private static final double LOG_OUT_DEGREE_MEAN =
      Math.log(MEAN_OUT_DEGREE / (1 - SINK_SHARE)) - OUT_DEGREE_SIGMA * OUT_DEGREE_SIGMA / 2;

  private SyntheticGraphs() {}

  public record Synthetic(GraphInput input, long[] edges) {

    boolean isInternal(CodeElementId id) {
      return input.types().stream().anyMatch(type -> type.id().equals(id));
    }
  }

  public static Synthetic generate(int types, int packages, long seed) {
    final SplittableRandom random = new SplittableRandom(seed);
    final int n = types;
    final int[] byPopularity = permutation(n, random);
    final double[] zipf = zipfCumulative(n - (int) Math.round(n * NEVER_TARGETED_SHARE));
    final int[] sinkOrder = permutation(n, random);
    final boolean[] sink = new boolean[n];
    for (int i = 0; i < Math.round(n * SINK_SHARE); i++) {
      sink[sinkOrder[i]] = true;
    }

    final List<TypeNode> nodes = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      final String pkg = "gen.p%02d".formatted(i % packages);
      nodes.add(
          new TypeNode(
              CodeElementId.ofType("%s.T%05d".formatted(pkg, i)), CodeElementId.ofPackage(pkg)));
    }
    final List<Dependency> rows = new ArrayList<>();
    for (int v = 0; v < n; v++) {
      final CodeElementId source = nodes.get(v).id();
      if (!sink[v]) {
        for (final int target : targetsOf(v, byPopularity, zipf, random)) {
          rows.addAll(pairRows(source, nodes.get(target).id(), random));
        }
      }
      rows.addAll(noiseRows(source, random));
    }
    final GraphInput input = new GraphInput(nodes, rows);
    return new Synthetic(input, internalEdges(input));
  }

  public static List<CodeElementId> sortedIds(GraphInput input) {
    return input.types().stream().map(TypeNode::id).sorted().toList();
  }

  public static long[] internalEdges(GraphInput input) {
    final List<CodeElementId> sorted = sortedIds(input);
    final Map<CodeElementId, Integer> index = new HashMap<>();
    for (int i = 0; i < sorted.size(); i++) {
      index.put(sorted.get(i), i);
    }
    return input.rows().stream()
        .filter(row -> index.containsKey(row.target()) && !row.source().equals(row.target()))
        .mapToLong(row -> edge(index.get(row.source()), index.get(row.target())))
        .distinct()
        .sorted()
        .toArray();
  }

  public static long edge(int source, int target) {
    return ((long) source << Integer.SIZE) | target;
  }

  public static int source(long edge) {
    return (int) (edge >>> Integer.SIZE);
  }

  public static int target(long edge) {
    return (int) edge;
  }

  public enum RankVector {
    TYPE_PAGERANK,
    TYPE_PAGERANK_OUT,
    PACKAGE_PAGERANK,
    PACKAGE_PAGERANK_OUT;

    public double[] inIdOrder(StructuralGraphResult result) {
      final Stream<TypeMetrics> types =
          result.types().stream().sorted(Comparator.comparing(TypeMetrics::id));
      final Stream<PackageMetrics> packages =
          result.packages().stream().sorted(Comparator.comparing(PackageMetrics::id));
      return switch (this) {
        case TYPE_PAGERANK -> types.mapToDouble(TypeMetrics::pagerank).toArray();
        case TYPE_PAGERANK_OUT -> types.mapToDouble(TypeMetrics::pagerankOut).toArray();
        case PACKAGE_PAGERANK -> packages.mapToDouble(PackageMetrics::pagerank).toArray();
        case PACKAGE_PAGERANK_OUT -> packages.mapToDouble(PackageMetrics::pagerankOut).toArray();
      };
    }
  }

  public static Graph<Integer, DefaultEdge> jgraphtGraph(int[] vertexOrder, long[] edges) {
    final Graph<Integer, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    for (final int vertex : vertexOrder) {
      graph.addVertex(vertex);
    }
    for (final long edge : edges) {
      assertThat(graph.addEdge(source(edge), target(edge))).as("aresta repetida").isNotNull();
    }
    return graph;
  }

  public static double[] jgraphtPageRank(Graph<Integer, DefaultEdge> graph) {
    return jgraphtPageRank(
        graph, AnalysisConstants.PAGERANK_MAX_ITER, AnalysisConstants.PAGERANK_TOLERANCE);
  }

  public static double[] jgraphtPageRank(
      Graph<Integer, DefaultEdge> graph, int maxIterations, double tolerance) {
    final Map<Integer, Double> scores =
        new PageRank<>(graph, AnalysisConstants.PAGERANK_DAMPING, maxIterations, tolerance)
            .getScores();
    final double[] vector = new double[graph.vertexSet().size()];
    for (int vertex = 0; vertex < vector.length; vertex++) {
      vector[vertex] = scores.get(vertex);
    }
    return vector;
  }

  public static long[] rawBits(double[] values) {
    return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
  }

  public static int[] permutation(int n, SplittableRandom random) {
    final int[] order = new int[n];
    Arrays.setAll(order, i -> i);
    for (int i = n - 1; i > 0; i--) {
      final int j = random.nextInt(i + 1);
      final int swapped = order[i];
      order[i] = order[j];
      order[j] = swapped;
    }
    return order;
  }

  public static long[] shuffled(long[] values, SplittableRandom random) {
    final int[] order = permutation(values.length, random);
    final long[] shuffled = new long[values.length];
    for (int i = 0; i < values.length; i++) {
      shuffled[i] = values[order[i]];
    }
    return shuffled;
  }

  public static <T> List<T> shuffled(List<T> values, long seed) {
    final List<T> copy = new ArrayList<>(values);
    Collections.shuffle(copy, new SplittableRandom(seed));
    return List.copyOf(copy);
  }

  private static TreeSet<Integer> targetsOf(
      int source, int[] byPopularity, double[] zipf, SplittableRandom random) {
    final long drawn =
        Math.round(Math.exp(LOG_OUT_DEGREE_MEAN + OUT_DEGREE_SIGMA * random.nextGaussian()));
    final int degree = (int) Math.max(1, Math.min(byPopularity.length / 4, drawn));
    final TreeSet<Integer> targets = new TreeSet<>();
    for (int attempt = 0;
        targets.size() < degree && attempt < TARGET_DRAW_ATTEMPTS_PER_EDGE * degree;
        attempt++) {
      final int found = Arrays.binarySearch(zipf, random.nextDouble() * zipf[zipf.length - 1]);
      final int rank = Math.min(found < 0 ? -found - 1 : found, zipf.length - 1);
      if (byPopularity[rank] != source) {
        targets.add(byPopularity[rank]);
      }
    }
    return targets;
  }

  private static List<Dependency> pairRows(
      CodeElementId source, CodeElementId target, SplittableRandom random) {
    final int first = random.nextInt(TYPE_EDGES.size());
    final int kinds = 1 + random.nextInt(3);
    final List<Dependency> rows = new ArrayList<>(kinds);
    for (int k = 0; k < kinds; k++) {
      final DependencyType type = TYPE_EDGES.get((first + k) % TYPE_EDGES.size());
      final ResolutionQuality resolution = random.nextInt(10) == 0 ? INFERRED : RESOLVED;
      rows.add(new Dependency(source, target, type, resolution, 1 + random.nextInt(5)));
    }
    return rows;
  }

  private static List<Dependency> noiseRows(CodeElementId source, SplittableRandom random) {
    final List<Dependency> rows = new ArrayList<>(2);
    if (random.nextInt(2) == 0) {
      final ExternalTarget external = EXTERNAL_TARGETS.get(random.nextInt(EXTERNAL_TARGETS.size()));
      rows.add(
          new Dependency(
              source, external.id(), FIELD_TYPE, external.resolution(), 1 + random.nextInt(3)));
    }
    if (random.nextInt(20) == 0) {
      rows.add(new Dependency(source, source, CALLS, RESOLVED, 1 + random.nextInt(3)));
    }
    return rows;
  }

  private static double[] zipfCumulative(int ranks) {
    final double[] cumulative = new double[ranks];
    double sum = 0;
    for (int rank = 0; rank < ranks; rank++) {
      sum += 1d / (rank + 1);
      cumulative[rank] = sum;
    }
    return cumulative;
  }

  private record ExternalTarget(CodeElementId id, ResolutionQuality resolution) {}
}
