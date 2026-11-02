package br.pucpr.tcc.strata.analyzer.graph;

import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.compute;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.golden;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.goldenInput;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.node;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.packageMetrics;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.pkg;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.resolved;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.row;
import static br.pucpr.tcc.strata.analyzer.graph.StructuralGraphInputs.type;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPLEMENTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

final class FanInOutTest {

  @Test
  void asAutolinhasFicamForaDeTodaMetricaEContamEmSelfPairs() {
    final GraphInput input =
        new GraphInput(
            List.of(node("p.C"), node("p.B"), node("p.A")),
            List.of(
                row("p.A", "p.A", CALLS, 3, RESOLVED),
                resolved("p.A", "p.B", CALLS),
                resolved("p.B", "p.B", FIELD_TYPE),
                row("p.B", "p.B", CALLS, 2, RESOLVED),
                resolved("p.C", "p.C", CALLS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.A"), 0, 1, 0, 1, 0, 0, null, 0),
            tuple(type("p.B"), 1, 0, 1, 0, 0, 0, null, 0),
            tuple(type("p.C"), 0, 0, 0, 0, 0, 0, null, 0));
    assertThat(packageMetrics(result, "p").internalCohesion()).isEqualTo(1);
    assertThat(result.packageEdges()).isEmpty();
    assertThat(result.counters())
        .isEqualTo(new GraphCounters(3, 1, 3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  void umParComTresTiposDeDependenciaContaUmaVez() {
    final GraphInput input =
        new GraphInput(
            List.of(node("q.B"), node("p.A")),
            List.of(
                row("p.A", "q.B", CALLS, 5, RESOLVED),
                row("p.A", "q.B", FIELD_TYPE, 2, RESOLVED),
                row("p.A", "q.B", IMPORTS, 1, RESOLVED)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.A"), 0, 1, 0, 1, 0, 0, null, 1),
            tuple(type("q.B"), 1, 0, 1, 0, 0, 1, null, 1));
    assertThat(result.counters().typeEdges()).isEqualTo(1);
    assertThat(result.packageEdges())
        .containsExactly(
            new PackageEdge(
                pkg("p"), pkg("q"), 1, Map.of(IMPORTS, 1, CALLS, 5, FIELD_TYPE, 2), 8, RESOLVED));
  }

  @Test
  void aIdaEAVoltaSaoDuasArestas() {
    final GraphInput input =
        new GraphInput(
            List.of(node("p.A"), node("p.B")),
            List.of(resolved("p.A", "p.B", CALLS), resolved("p.B", "p.A", FIELD_TYPE)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.A"), 1, 1, 1, 1, 0, 0, new Cycle(1, 2), 0),
            tuple(type("p.B"), 1, 1, 1, 1, 0, 0, new Cycle(1, 2), 0));
    assertThat(result.counters().typeEdges()).isEqualTo(2);
  }

  @Test
  void oFanOutExternalTiraSoJavaLangEJavaUtilExatos() {
    final GraphInput input =
        new GraphInput(
            List.of(node("p.A")),
            List.of(
                row("p.A", "java.lang.String", FIELD_TYPE, 2, RESOLVED),
                resolved("p.A", "java.util.List", FIELD_TYPE),
                resolved("p.A", "java.util.List", IMPORTS),
                resolved("p.A", "java.util.concurrent.Callable", IMPLEMENTS),
                row("p.A", "org.lib.Missing", CALLS, 3, UNRESOLVED_EXTERNAL),
                row("p.A", "org.lib.Missing", IMPORTS, 1, UNRESOLVED_EXTERNAL)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(tuple(type("p.A"), 0, 0, 0, 0, 2, 0, null, 0));
    assertThat(result.packages()).extracting(PackageMetrics::id).containsExactly(pkg("p"));
    assertThat(result.packageEdges()).isEmpty();
    assertThat(result.counters())
        .isEqualTo(new GraphCounters(1, 0, 0, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  void umNomeNuContaEUmAninhadoDeJavaUtilNao() {
    final GraphInput input =
        new GraphInput(
            List.of(node("p.B"), node("p.A")),
            List.of(
                resolved("p.A", "p.B", CALLS),
                row("p.A", "org.lib.Missing", CALLS, 1, UNRESOLVED_EXTERNAL),
                row("p.B", "Missing", PARAMETER_TYPE, 1, UNRESOLVED_EXTERNAL),
                row("p.B", "org.lib.Missing", FIELD_TYPE, 1, UNRESOLVED_EXTERNAL),
                resolved("p.B", "java.util.Map$Entry", PARAMETER_TYPE)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.A"), 0, 1, 0, 1, 1, 0, null, 0),
            tuple(type("p.B"), 1, 0, 1, 0, 2, 0, null, 0));
    assertThat(result.counters().externalPairs()).isEqualTo(3);
    assertThat(result.counters().denylistedPairs()).isEqualTo(1);
    assertThat(result.counters().typeNodes()).isEqualTo(2);
    assertThat(result.counters().typeEdges()).isEqualTo(1);
  }

  @Test
  void osFansResolvidosContamSoArestasCujaQualidadeAgregadaEResolved() {
    final GraphInput input =
        new GraphInput(
            List.of(node("p.D"), node("p.C"), node("p.B"), node("p.A")),
            List.of(
                resolved("p.A", "p.B", CALLS),
                row("p.A", "p.B", FIELD_TYPE, 1, INFERRED),
                resolved("p.A", "p.B", IMPORTS),
                resolved("p.A", "p.C", CALLS),
                resolved("p.D", "p.B", IMPORTS),
                row("p.D", "p.C", CALLS, 1, INFERRED)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.A"), 0, 2, 0, 1, 0, 0, null, 0),
            tuple(type("p.B"), 2, 0, 1, 0, 0, 0, null, 0),
            tuple(type("p.C"), 2, 0, 1, 0, 0, 0, null, 0),
            tuple(type("p.D"), 0, 2, 0, 1, 0, 0, null, 0));
  }

  @Test
  void externalFanInContaFontesEBridgeSpanContaPacotesAlheios() {
    final GraphInput input =
        new GraphInput(
            List.of(
                node("r.S4"),
                node("q.Y"),
                node("p.T"),
                node("r.S3"),
                node("q.X"),
                node("p.Z"),
                node("q.S2"),
                node("p.S1")),
            List.of(
                resolved("p.S1", "p.T", CALLS),
                resolved("q.S2", "p.T", CALLS),
                resolved("r.S3", "p.T", CALLS),
                resolved("r.S4", "p.T", CALLS),
                resolved("p.T", "q.X", CALLS),
                resolved("p.T", "q.Y", CALLS),
                resolved("p.T", "p.Z", CALLS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("p.S1"), 0, 1, 0, 1, 0, 0, null, 0),
            tuple(type("p.T"), 4, 3, 4, 3, 0, 3, null, 3),
            tuple(type("p.Z"), 1, 0, 1, 0, 0, 0, null, 0),
            tuple(type("q.S2"), 0, 1, 0, 1, 0, 0, null, 1),
            tuple(type("q.X"), 1, 0, 1, 0, 0, 1, null, 1),
            tuple(type("q.Y"), 1, 0, 1, 0, 0, 1, null, 1),
            tuple(type("r.S3"), 0, 1, 0, 1, 0, 0, null, 1),
            tuple(type("r.S4"), 0, 1, 0, 1, 0, 0, null, 1));
  }

  @Test
  void oBridgeSpanDoCiclicoEUmParaMainTresParaOrderDoisParaOrderDto() throws Exception {
    final GraphInput input = goldenInput(golden("cyclic"));

    final StructuralGraphResult result = compute(input);

    final Cycle billing = new Cycle(1, 3);
    final Cycle orders = new Cycle(2, 2);
    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("com.x.app.Main"), 0, 1, 0, 1, 0, 0, null, 1),
            tuple(type("com.x.billing.Invoice"), 1, 1, 1, 1, 0, 0, billing, 0),
            tuple(type("com.x.billing.Ledger"), 1, 1, 1, 1, 0, 0, billing, 0),
            tuple(type("com.x.billing.Posting"), 1, 1, 1, 1, 0, 0, billing, 0),
            tuple(type("com.x.orders.Order"), 2, 1, 2, 1, 0, 2, orders, 3),
            tuple(type("com.x.orders.dto.OrderDto"), 1, 1, 1, 1, 0, 1, orders, 2));
  }

  @Test
  void deInnerParaOuterEUmaArestaComum() {
    final GraphInput input =
        new GraphInput(
            List.of(node("a.Outer$Inner"), node("a.Outer")),
            List.of(resolved("a.Outer$Inner", "a.Outer", FIELD_TYPE)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("a.Outer"), 1, 0, 1, 0, 0, 0, null, 0),
            tuple(type("a.Outer$Inner"), 0, 1, 0, 1, 0, 0, null, 0));
    assertThat(packageMetrics(result, "a").internalCohesion()).isEqualTo(1);
    assertThat(result.packageEdges()).isEmpty();
    assertThat(result.counters().selfPairs()).isZero();
    assertThat(result.counters().typeEdges()).isEqualTo(1);
  }

  @Test
  void innerEOuterComIdaEVoltaFormamUmCicloDeDois() {
    final GraphInput input =
        new GraphInput(
            List.of(node("a.Outer"), node("a.Outer$Inner")),
            List.of(
                resolved("a.Outer$Inner", "a.Outer", FIELD_TYPE),
                resolved("a.Outer", "a.Outer$Inner", CALLS)));

    final StructuralGraphResult result = compute(input);

    assertThat(result.types())
        .extracting(FanInOutTest::counts)
        .containsExactly(
            tuple(type("a.Outer"), 1, 1, 1, 1, 0, 0, new Cycle(1, 2), 0),
            tuple(type("a.Outer$Inner"), 1, 1, 1, 1, 0, 0, new Cycle(1, 2), 0));
    assertThat(packageMetrics(result, "a").cycle()).isNull();
    assertThat(packageMetrics(result, "a").internalCohesion()).isEqualTo(2);
    assertThat(result.counters().typeCycles()).isEqualTo(1);
    assertThat(result.counters().typesInCycles()).isEqualTo(2);
    assertThat(result.counters().packageCycles()).isZero();
  }

  @Test
  void umaLinhaDeOrigemForaDaEntradaEDefeito() {
    final GraphInput input =
        new GraphInput(List.of(node("p.A")), List.of(resolved("p.Ghost", "p.A", CALLS)));

    assertThatThrownBy(() -> compute(input)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Tuple counts(TypeMetrics metrics) {
    return tuple(
        metrics.id(),
        metrics.fanIn(),
        metrics.fanOut(),
        metrics.fanInResolved(),
        metrics.fanOutResolved(),
        metrics.fanOutExternal(),
        metrics.externalFanIn(),
        metrics.cycle(),
        metrics.bridgeSpan());
  }
}
