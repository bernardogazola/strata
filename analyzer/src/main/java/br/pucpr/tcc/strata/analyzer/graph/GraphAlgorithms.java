package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.alg.scoring.BetweennessCentrality;
import org.jgrapht.alg.scoring.BetweennessCentrality.OverflowStrategy;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.graph.DefaultEdge;

public final class GraphAlgorithms {

  private static final int MIN_CYCLE_SIZE = 2;

  private static final Comparator<Set<Integer>> BY_MINIMUM_MEMBER =
      Comparator.comparingInt(Collections::min);

  private GraphAlgorithms() {}

  public static PageRankVector pageRank(Graph<Integer, DefaultEdge> graph, int maxIterations) {
    requireUnweighted(graph);
    final int vertexCount = DenseGraphs.requireDenseVertices(graph);
    final Map<Integer, Double> atCap = scores(graph, maxIterations);
    final Map<Integer, Double> pastCap = scores(graph, Math.addExact(maxIterations, 1));
    final double[] vector = new double[vertexCount];
    for (int vertex = 0; vertex < vertexCount; vertex++) {
      vector[vertex] = atCap.get(vertex);
    }
    return new PageRankVector(vector, atCap.equals(pastCap));
  }

  public static CycleAssignment stronglyConnected(Graph<Integer, DefaultEdge> graph) {
    final int vertexCount = DenseGraphs.requireDenseVertices(graph);
    final List<Set<Integer>> cycles =
        new KosarajuStrongConnectivityInspector<>(graph)
            .stronglyConnectedSets().stream()
                .filter(component -> component.size() >= MIN_CYCLE_SIZE)
                .sorted(BY_MINIMUM_MEMBER)
                .toList();
    final Cycle[] assignment = new Cycle[vertexCount];
    for (int position = 0; position < cycles.size(); position++) {
      final Set<Integer> members = cycles.get(position);
      final Cycle cycle = new Cycle(position + 1, members.size());
      for (final int member : members) {
        assignment[member] = cycle;
      }
    }
    return new CycleAssignment(assignment, cycles.size());
  }

  public static int[] weakComponents(Graph<Integer, DefaultEdge> graph) {
    final int vertexCount = DenseGraphs.requireDenseVertices(graph);
    final List<Set<Integer>> components =
        new ConnectivityInspector<>(graph)
            .connectedSets().stream().sorted(BY_MINIMUM_MEMBER).toList();
    final int[] assignment = new int[vertexCount];
    for (int position = 0; position < components.size(); position++) {
      for (final int member : components.get(position)) {
        assignment[member] = position + 1;
      }
    }
    return assignment;
  }

  public static Optional<double[]> betweenness(Graph<Integer, DefaultEdge> graph, int maxNodes) {
    return betweenness(graph, maxNodes, AnalysisConstants.BETWEENNESS_MAX_EDGES);
  }

  static Optional<double[]> betweenness(
      Graph<Integer, DefaultEdge> graph, int maxNodes, int maxEdges) {
    requireUnweighted(graph);
    final int vertexCount = DenseGraphs.requireDenseVertices(graph);
    if (vertexCount > maxNodes || graph.edgeSet().size() > maxEdges) {
      return Optional.empty();
    }
    final Map<Integer, Double> scores;
    try {
      scores =
          new BetweennessCentrality<>(graph, false, OverflowStrategy.THROW_EXCEPTION_ON_OVERFLOW)
              .getScores();
    } catch (ArithmeticException overflow) {
      Log.warn(
          "betweenness não calculada: a contagem de caminhos mínimos estoura um long ("
              + vertexCount
              + " vértices, "
              + graph.edgeSet().size()
              + " arestas)");
      return Optional.empty();
    }
    final double[] values = new double[vertexCount];
    for (int vertex = 0; vertex < vertexCount; vertex++) {
      values[vertex] = scores.get(vertex);
    }
    return Optional.of(values);
  }

  private static Map<Integer, Double> scores(Graph<Integer, DefaultEdge> graph, int maxIterations) {
    return new PageRank<>(
            graph,
            AnalysisConstants.PAGERANK_DAMPING,
            maxIterations,
            AnalysisConstants.PAGERANK_TOLERANCE)
        .getScores();
  }

  private static void requireUnweighted(Graph<Integer, DefaultEdge> graph) {
    if (graph.getType().isWeighted()) {
      throw new IllegalArgumentException("os algoritmos do grafo estrutural não usam peso");
    }
  }
}
