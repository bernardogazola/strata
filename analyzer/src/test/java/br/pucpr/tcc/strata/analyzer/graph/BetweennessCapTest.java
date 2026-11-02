package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.resolved;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.ArrayList;
import java.util.List;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class BetweennessCapTest {

  private static final int VERTICES = 10;
  private static final int EDGES = VERTICES * (VERTICES - 1) / 2;

  private static final int WIDTH = 2;

  private static final int OVERFLOWING_LAYERS = 66;

  private static final int FITTING_LAYERS = 62;

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

  @Test
  void quandoAContagemDeCaminhosMinimosEstouraUmLongAIntermediacaoNaoECalculada() {
    final Graph<Integer, DefaultEdge> graph = layered(OVERFLOWING_LAYERS);

    assertThat(graph.vertexSet()).as("132 vértices, abaixo do teto de 1 000").hasSize(132);
    assertThat(graph.edgeSet()).as("2 · 2 · 65 = 260 arestas, abaixo de 20 000").hasSize(260);
    assertThat(
            GraphAlgorithms.betweenness(
                graph,
                AnalysisConstants.BETWEENNESS_MAX_NODES,
                AnalysisConstants.BETWEENNESS_MAX_EDGES))
        .isEmpty();
  }

  @Test
  void com62CamadasOsCaminhosCabemEAIntermediacaoEADerivadaAMao() {
    final double[] values =
        GraphAlgorithms.betweenness(
                layered(FITTING_LAYERS),
                AnalysisConstants.BETWEENNESS_MAX_NODES,
                AnalysisConstants.BETWEENNESS_MAX_EDGES)
            .orElseThrow();

    final double[] expected = new double[FITTING_LAYERS * WIDTH];
    for (int vertex = 0; vertex < expected.length; vertex++) {
      final int layer = vertex / WIDTH;
      expected[vertex] = (double) WIDTH * layer * (FITTING_LAYERS - 1 - layer);
    }
    assertThat(values).containsExactly(expected);
  }

  @ParameterizedTest(name = "{0} camadas → betweennessSkipped {1}")
  @CsvSource({"66, 1", "62, 0"})
  void peloComputeOEstouroDeixaAIntermediacaoNulaEMarcaOContador(int layers, long skipped) {
    final List<TypeNode> types = new ArrayList<>();
    final List<Dependency> rows = new ArrayList<>();
    for (int layer = 0; layer < layers; layer++) {
      for (int position = 0; position < WIDTH; position++) {
        types.add(node(layeredType(layer, position)));
        for (int next = 0; layer + 1 < layers && next < WIDTH; next++) {
          rows.add(
              resolved(
                  layeredType(layer, position),
                  layeredType(layer + 1, next),
                  DependencyType.FIELD_TYPE));
        }
      }
    }

    final StructuralGraphResult result = StructuralGraphInputs.compute(new GraphInput(types, rows));

    assertThat(result.counters().packageNodes()).isEqualTo((long) layers * WIDTH);
    assertThat(result.counters().packageEdges()).isEqualTo((long) (layers - 1) * WIDTH * WIDTH);
    assertThat(result.counters().betweennessSkipped()).isEqualTo(skipped);
    if (skipped == 1) {
      assertThat(result.packages()).allSatisfy(pkg -> assertThat(pkg.betweenness()).isNull());
    } else {
      assertThat(result.packages()).allSatisfy(pkg -> assertThat(pkg.betweenness()).isNotNull());
    }
  }

  private static String layeredType(int layer, int position) {
    return "l" + layer + "w" + position + ".T";
  }

  private static Graph<Integer, DefaultEdge> layered(int layers) {
    final Graph<Integer, DefaultEdge> graph = new SimpleDirectedGraph<>(DefaultEdge.class);
    for (int vertex = 0; vertex < layers * WIDTH; vertex++) {
      graph.addVertex(vertex);
    }
    for (int layer = 0; layer + 1 < layers; layer++) {
      for (int from = 0; from < WIDTH; from++) {
        for (int to = 0; to < WIDTH; to++) {
          graph.addEdge(layer * WIDTH + from, (layer + 1) * WIDTH + to);
        }
      }
    }
    return graph;
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
