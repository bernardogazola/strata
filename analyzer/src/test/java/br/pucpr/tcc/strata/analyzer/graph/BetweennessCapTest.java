package br.pucpr.tcc.strata.analyzer.graph;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.junit.jupiter.api.Test;

final class BetweennessCapTest {

  private static final int VERTICES = 10;
  private static final int EDGES = VERTICES * (VERTICES - 1) / 2;

  @Test
  void acimaDoTetoDeArestasAIntermediacaoNaoECalculadaMesmoAbaixoDoDeVertices() {
    final Graph<Integer, DefaultEdge> graph = completeDag();

    assertThat(GraphAlgorithms.betweenness(graph, VERTICES, EDGES)).isPresent();
    assertThat(GraphAlgorithms.betweenness(graph, VERTICES, EDGES - 1)).isEmpty();
    assertThat(GraphAlgorithms.betweenness(graph, VERTICES - 1, EDGES)).isEmpty();
  }

  @Test
  void aSobrecargaDeProducaoUsaOTetoDeArestasDasConstantes() {
    assertThat(AnalysisConstants.BETWEENNESS_MAX_EDGES).isEqualTo(20_000);
    assertThat(GraphAlgorithms.betweenness(completeDag(), VERTICES).orElseThrow())
        .containsExactly(GraphAlgorithms.betweenness(completeDag(), VERTICES, EDGES).orElseThrow());
  }

  @Test
  void noDagCompletoNenhumVerticeIntermedeia() {
    final double[] values =
        GraphAlgorithms.betweenness(completeDag(), VERTICES, EDGES).orElseThrow();

    assertThat(values).hasSize(VERTICES).containsOnly(0d);
  }

  private static Graph<Integer, DefaultEdge> completeDag() {
    final Graph<Integer, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    for (int vertex = 0; vertex < VERTICES; vertex++) {
      graph.addVertex(vertex);
    }
    for (int from = 0; from < VERTICES; from++) {
      for (int to = from + 1; to < VERTICES; to++) {
        graph.addEdge(from, to);
      }
    }
    return graph;
  }
}
