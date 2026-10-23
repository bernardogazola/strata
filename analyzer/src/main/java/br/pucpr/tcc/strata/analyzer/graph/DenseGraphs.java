package br.pucpr.tcc.strata.analyzer.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.AsUnmodifiableGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;

final class DenseGraphs {

  private DenseGraphs() {}

  static Graph<Integer, DefaultEdge> forward(int vertexCount, int[] sources, int[] targets) {
    final Graph<Integer, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    for (int vertex = 0; vertex < vertexCount; vertex++) {
      graph.addVertex(vertex);
    }
    for (int edge = 0; edge < sources.length; edge++) {
      if (graph.addEdge(sources[edge], targets[edge]) == null) {
        throw new IllegalStateException(
            "um par repetido " + sources[edge] + " -> " + targets[edge]);
      }
    }
    return new AsUnmodifiableGraph<>(graph);
  }

  static int requireDenseVertices(Graph<Integer, DefaultEdge> graph) {
    int expected = 0;
    for (final Integer vertex : graph.vertexSet()) {
      if (vertex == null || vertex != expected) {
        throw new IllegalArgumentException(
            "os vértices devem ser 0..n-1 em ordem crescente; veio " + vertex + " em " + expected);
      }
      expected++;
    }
    return expected;
  }
}
