package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.compute;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.pkg;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.resolved;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PackageOrderTest {

  @Test
  void oPacotePadraoVemPrimeiroEEOComponenteUmMesmoComOTipoDeleDepois() {
    final GraphInput input = new GraphInput(List.of(node("zeta"), node("a.B")), List.of());

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .as("a premissa: o tipo do pacote padrão é o segundo tipo")
        .extracting(TypeMetrics::id)
        .containsExactly(type("a.B"), type("zeta"));
    assertThat(result.packages())
        .as("sem aresta, cada pacote é um componente, numerado pelo menor id")
        .extracting(PackageMetrics::id, PackageMetrics::componentId)
        .containsExactly(tuple(pkg(""), 1), tuple(pkg("a"), 2));
  }

  @Test
  void comUmaArestaOPacotePadraoContinuaPrimeiroEAArestaSaiDele() {
    final GraphInput input =
        new GraphInput(
            List.of(node("zeta"), node("a.B")),
            List.of(resolved("zeta", "a.B", DependencyType.FIELD_TYPE)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.packages())
        .extracting(PackageMetrics::id, PackageMetrics::componentId, PackageMetrics::fanOut)
        .containsExactly(tuple(pkg(""), 1, 1), tuple(pkg("a"), 1, 0));
    assertThat(result.packageEdges())
        .extracting(PackageEdge::source, PackageEdge::target)
        .containsExactly(tuple(pkg(""), pkg("a")));
    assertThat(result.packages().get(1).pagerank())
        .isGreaterThan(result.packages().get(0).pagerank());
  }
}
