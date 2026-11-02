package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.PACKAGES;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.resolved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

final class CancellationPointsTest {

  private static final int FIXED_BOUNDARIES = 13;

  private static final GraphInput INPUT =
      new GraphInput(
          List.of(
              node("a.A"), node("a.A2"), node("b.B"), node("c.C"), node("c.D"), node("c.Lonely")),
          List.of(
              resolved("a.A", "b.B", DependencyType.FIELD_TYPE),
              resolved("a.A", "a.A2", DependencyType.CALLS),
              resolved("a.A2", "java.util.List", DependencyType.PARAMETER_TYPE),
              resolved("b.B", "c.C", DependencyType.FIELD_TYPE),
              resolved("c.C", "b.B", DependencyType.RETURN_TYPE),
              resolved("c.D", "c.C", DependencyType.EXTENDS)));

  private static final int TYPES = INPUT.types().size();

  private static final class CountingSupplier implements BooleanSupplier {
    private final int flipAt;
    private int polls;

    CountingSupplier(int flipAt) {
      this.flipAt = flipAt;
    }

    @Override
    public boolean getAsBoolean() {
      polls++;
      return flipAt > 0 && polls >= flipAt;
    }
  }

  @Test
  void semCancelamentoOComputeConsultaUmaVezPorGrupoDeOrigemEEntreCadaPasso() {
    final CountingSupplier never = new CountingSupplier(0);

    StructuralGraphAnalysis.compute(INPUT, PACKAGES, never);

    assertThat(never.polls)
        .as("um por grupo de origem (%d tipos) mais os %d pontos fixos", TYPES, FIXED_BOUNDARIES)
        .isGreaterThanOrEqualTo(TYPES + FIXED_BOUNDARIES);
  }

  @Test
  void aPrimeiraConsultaQueVeOPedidoParaOCalculoEmQualquerPonto() {
    final CountingSupplier never = new CountingSupplier(0);
    StructuralGraphAnalysis.compute(INPUT, PACKAGES, never);
    final int total = never.polls;
    assertThat(total).as("anti-vacuidade").isGreaterThanOrEqualTo(TYPES + FIXED_BOUNDARIES);

    for (int flipAt = 1; flipAt <= total; flipAt++) {
      final CountingSupplier flipping = new CountingSupplier(flipAt);

      assertThatThrownBy(() -> StructuralGraphAnalysis.compute(INPUT, PACKAGES, flipping))
          .as("virado na consulta %d de %d", flipAt, total)
          .isInstanceOf(CancellationException.class);
      assertThat(flipping.polls)
          .as("parou na consulta que viu o pedido, a %d de %d", flipAt, total)
          .isEqualTo(flipAt);
    }
  }

  @Test
  void aMontagemEAProjecaoParamComOCancelamentoJaPedido() {
    assertThatThrownBy(() -> StructuralGraphBuilder.build(INPUT, PACKAGES, () -> true))
        .isInstanceOf(CancellationException.class);
    final StructuralGraph graph = StructuralGraphBuilder.build(INPUT, PACKAGES, () -> false);

    assertThatThrownBy(() -> PackageProjection.project(graph, () -> true))
        .isInstanceOf(CancellationException.class);
  }
}
