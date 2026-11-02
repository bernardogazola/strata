package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.compute;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.golden;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.goldenInput;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.packageMetrics;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.row;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.typeMetrics;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ResolutionMixTest {

  @Test
  void aLinhaPropriaEAExternaNaoResolvidaContamPelaQualidadeDaLinha() {
    final GraphInput input =
        new GraphInput(
            List.of(node("q.C"), node("p.B"), node("p.A")),
            List.of(
                row("p.B", "com.acme.Lib", CALLS, 5, UNRESOLVED_EXTERNAL),
                row("p.A", "p.A", CALLS, 3, RESOLVED),
                row("p.A", "Gizmo", PARAMETER_TYPE, 4, UNRESOLVED_EXTERNAL),
                row("p.B", "q.C", CALLS, 2, INFERRED),
                row("p.A", "p.B", FIELD_TYPE, 1, INFERRED),
                row("p.A", "java.lang.String", FIELD_TYPE, 1, RESOLVED),
                row("p.B", "q.C", IMPORTS, 1, INFERRED),
                row("p.A", "p.B", CALLS, 2, RESOLVED)));

    final StructuralGraphResult result = compute(input);

    assertThat(typeMetrics(result, "p.A").resolutionMix()).isEqualTo(new ResolutionMix(6, 1, 4));
    assertThat(typeMetrics(result, "p.B").resolutionMix()).isEqualTo(new ResolutionMix(0, 3, 5));
    assertThat(typeMetrics(result, "q.C").resolutionMix()).isEqualTo(ResolutionMix.NONE);
    assertThat(packageMetrics(result, "p").resolutionMix()).isEqualTo(new ResolutionMix(6, 4, 9));
    assertThat(packageMetrics(result, "q").resolutionMix()).isEqualTo(ResolutionMix.NONE);
    assertThat(typeMetrics(result, "p.A").fanOut()).isEqualTo(1);
    assertThat(typeMetrics(result, "p.A").fanOutResolved())
        .as("a aresta A -> B é INFERRED: a mistura não é a qualidade da aresta")
        .isZero();
    assertThat(result.counters().selfPairs()).isEqualTo(1);
  }

  @Test
  void aMisturaDoCyclicEhADerivadaAMaoDoGolden() throws Exception {
    final StructuralGraphResult result = compute(goldenInput(golden("cyclic")));

    assertThat(result.types())
        .extracting(metricas -> metricas.id().value() + " " + metricas.resolutionMix())
        .containsExactly(
            "type:com.x.app.Main " + new ResolutionMix(4, 0, 0),
            "type:com.x.billing.Invoice " + new ResolutionMix(2, 0, 0),
            "type:com.x.billing.Ledger " + new ResolutionMix(2, 0, 0),
            "type:com.x.billing.Posting " + new ResolutionMix(2, 0, 0),
            "type:com.x.orders.Order " + new ResolutionMix(3, 0, 0),
            "type:com.x.orders.dto.OrderDto " + new ResolutionMix(3, 0, 0));
    assertThat(result.packages())
        .extracting(metricas -> metricas.id().value() + " " + metricas.resolutionMix())
        .containsExactly(
            "pkg:com.x.app " + new ResolutionMix(4, 0, 0),
            "pkg:com.x.billing " + new ResolutionMix(6, 0, 0),
            "pkg:com.x.orders " + new ResolutionMix(3, 0, 0),
            "pkg:com.x.orders.dto " + new ResolutionMix(3, 0, 0));
  }

  @Test
  void umaContagemNegativaEhRecusada() {
    assertThatThrownBy(() -> new ResolutionMix(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResolutionMix(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResolutionMix(0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ResolutionMix.NONE).isEqualTo(new ResolutionMix(0, 0, 0));
  }
}
