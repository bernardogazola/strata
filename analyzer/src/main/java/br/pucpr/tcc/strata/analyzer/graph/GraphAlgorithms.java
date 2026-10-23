package br.pucpr.tcc.strata.analyzer.graph;

import java.util.Optional;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class GraphAlgorithms {

  private GraphAlgorithms() {}

  public static PageRankVector pageRank(Graph<Integer, DefaultEdge> graph, int maxIterations) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static CycleAssignment stronglyConnected(Graph<Integer, DefaultEdge> graph) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static int[] weakComponents(Graph<Integer, DefaultEdge> graph) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static Optional<double[]> betweenness(Graph<Integer, DefaultEdge> graph, int maxNodes) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
