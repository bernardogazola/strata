package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SliceRankingTest {

  private static final String FULLWIDTH_A = Character.toString(0xFF21);
  private static final String MATH_BOLD_A = Character.toString(0x1D400);

  private int nextEdge;

  private static SliceNode type(String name, double pagerank) {
    return new SliceNode(
        "type:p." + name, GraphNodeKind.TYPE, name, 1, 1, 0, 0, pagerank, ResolutionMix.NONE);
  }

  private static SliceNode pkg(String name, double pagerank) {
    return new SliceNode(
        "pkg:" + name, GraphNodeKind.PACKAGE, name, 1, 1, 0, 0, pagerank, ResolutionMix.NONE);
  }

  private static SliceNode group(String prefix, double pagerank) {
    return new SliceNode(
        "pfx:" + prefix,
        GraphNodeKind.PACKAGE_PREFIX,
        prefix,
        1,
        2,
        0,
        0,
        pagerank,
        ResolutionMix.NONE);
  }

  private SliceEdge edge(SliceNode source, SliceNode target, long weight) {
    nextEdge++;
    return new SliceEdge(
        String.format("e_%016x", nextEdge),
        source.id(),
        target.id(),
        Map.of(DependencyType.CALLS, weight),
        weight,
        ResolutionQuality.RESOLVED);
  }

  private static List<String> ids(List<SliceNode> nodes) {
    return nodes.stream().map(SliceNode::id).toList();
  }

  @Test
  void semCorteTudoFicaNaOrdemDoFioETruncatedEFalso() {
    final SliceNode a = type("A", 0.2);
    final SliceNode b = type("B", 0.5);
    final SliceNode c = type("C", 0.3);
    final SliceEdge cb = edge(c, b, 1);
    final SliceEdge ab = edge(a, b, 7);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(c, a, b), List.of(cb, ab), new SliceBudget(3, 2));

    assertThat(selection.nodes()).containsExactly(a, b, c);
    assertThat(selection.edges()).containsExactly(ab, cb);
    assertThat(selection.truncated()).isFalse();
  }

  @Test
  void umaFatiaVaziaNaoETruncada() {
    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(), List.of(), new SliceBudget(300, 1200));

    assertThat(selection.nodes()).isEmpty();
    assertThat(selection.edges()).isEmpty();
    assertThat(selection.truncated()).isFalse();
  }

  @Test
  void osNosFicamPorPagerankDecrescenteESaemPorId() {
    final SliceNode a = type("A", 0.4);
    final SliceNode b = type("B", 0.1);
    final SliceNode c = type("C", 0.5);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(a, b, c), List.of(), new SliceBudget(2, 10));

    assertThat(selection.nodes()).containsExactly(a, c);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void oEmpateDePagerankEDesfeitoPorIdEmPontoDeCodigo() {
    final SliceNode fullwidth = type(FULLWIDTH_A, 0.3);
    final SliceNode mathBold = type(MATH_BOLD_A, 0.3);
    assertThat(mathBold.id().compareTo(fullwidth.id()))
        .as("a armadilha: em UTF-16 o fora do plano básico vem antes")
        .isNegative();

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(mathBold, fullwidth), List.of(), new SliceBudget(1, 10));

    assertThat(selection.nodes()).containsExactly(fullwidth);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void aOrdemDoFioDosNosEPontoDeCodigo() {
    final SliceNode fullwidth = type(FULLWIDTH_A, 0.3);
    final SliceNode mathBold = type(MATH_BOLD_A, 0.3);
    final SliceNode z = type("Z", 0.1);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(z, mathBold, fullwidth), List.of(), new SliceBudget(2, 10));

    assertThat(ids(selection.nodes())).containsExactly(fullwidth.id(), mathBold.id());
  }

  @Test
  void osGruposSaemAntesDosPacotes() {
    final SliceNode a = pkg("a", 0.5);
    final SliceNode z = group("z", 0.5);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(a, z), List.of(), new SliceBudget(300, 1200));

    assertThat(selection.nodes()).containsExactly(z, a);
    assertThat(selection.truncated()).isFalse();
  }

  @Test
  void soFicamAsArestasEntreNosMantidos() {
    final SliceNode a = type("A", 0.5);
    final SliceNode b = type("B", 0.3);
    final SliceNode c = type("C", 0.2);
    final SliceEdge ab = edge(a, b, 1);
    final SliceEdge ac = edge(a, c, 5);
    final SliceEdge cb = edge(c, b, 2);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(a, b, c), List.of(ac, cb, ab), new SliceBudget(2, 10));

    assertThat(selection.nodes()).containsExactly(a, b);
    assertThat(selection.edges()).containsExactly(ab);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void asArestasFicamPorPesoDecrescenteEOEmpatePorParNaOrdemDoFio() {
    final SliceNode a = type("A", 0.4);
    final SliceNode b = type("B", 0.3);
    final SliceNode c = type("C", 0.3);
    final SliceEdge ca = edge(c, a, 5);
    final SliceEdge ac = edge(a, c, 3);
    final SliceEdge ba = edge(b, a, 3);
    final SliceEdge ab = edge(a, b, 1);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(a, b, c), List.of(ab, ba, ac, ca), new SliceBudget(3, 2));

    assertThat(selection.nodes()).containsExactly(a, b, c);
    assertThat(selection.edges()).containsExactly(ac, ca);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void oEmpateDeArestasEPontoDeCodigoNaOrigemENoDestino() {
    final SliceNode fullwidth = type(FULLWIDTH_A, 0.3);
    final SliceNode mathBold = type(MATH_BOLD_A, 0.3);
    final SliceNode z = type("Z", 0.3);
    final List<SliceNode> nodes = List.of(z, mathBold, fullwidth);
    final SliceEdge fromFullwidth = edge(fullwidth, z, 2);
    final SliceEdge fromMathBold = edge(mathBold, z, 2);
    final SliceEdge toFullwidth = edge(z, fullwidth, 2);
    final SliceEdge toMathBold = edge(z, mathBold, 2);

    final SliceRanking.Selection bySource =
        SliceRanking.select(nodes, List.of(fromMathBold, fromFullwidth), new SliceBudget(3, 1));
    final SliceRanking.Selection byTarget =
        SliceRanking.select(nodes, List.of(toMathBold, toFullwidth), new SliceBudget(3, 1));

    assertThat(bySource.edges()).containsExactly(fromFullwidth);
    assertThat(byTarget.edges()).containsExactly(toFullwidth);
  }

  @Test
  void aOrdemDoFioDasArestasEPontoDeCodigo() {
    final SliceNode fullwidth = type(FULLWIDTH_A, 0.3);
    final SliceNode mathBold = type(MATH_BOLD_A, 0.3);
    final SliceNode z = type("Z", 0.3);
    final SliceEdge fromFullwidth = edge(fullwidth, z, 1);
    final SliceEdge fromMathBold = edge(mathBold, z, 9);

    final SliceRanking.Selection selection =
        SliceRanking.select(
            List.of(z, mathBold, fullwidth),
            List.of(fromMathBold, fromFullwidth),
            new SliceBudget(3, 2));

    assertThat(selection.edges()).containsExactly(fromFullwidth, fromMathBold);
    assertThat(selection.truncated()).isFalse();
  }

  @Test
  void exatamenteNoOrcamentoNadaETruncado() {
    final SliceNode a = type("A", 0.5);
    final SliceNode b = type("B", 0.5);
    final SliceEdge ab = edge(a, b, 1);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(a, b), List.of(ab), new SliceBudget(2, 1));

    assertThat(selection.truncated()).isFalse();
  }

  @Test
  void oTransbordoDaRegraGCortaOGrupoDeMenorPagerank() {
    final List<SliceNode> groups = new ArrayList<>();
    groups.add(pkg("g000", 0.0001));
    for (int i = 1; i <= 300; i++) {
      groups.add(pkg(String.format("g%03d", i), 0.0033));
    }
    final SliceEdge cut = edge(groups.get(0), groups.get(1), 4);
    final SliceEdge kept = edge(groups.get(1), groups.get(2), 1);

    final SliceRanking.Selection selection =
        SliceRanking.select(groups, List.of(cut, kept), new SliceBudget(300, 1200));

    assertThat(selection.nodes()).hasSize(300).containsExactlyElementsOf(groups.subList(1, 301));
    assertThat(selection.edges()).containsExactly(kept);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void oTransbordoComPagerankIgualCortaOMaiorId() {
    final List<SliceNode> groups = new ArrayList<>();
    for (int i = 0; i <= 300; i++) {
      groups.add(pkg(String.format("g%03d", i), 0.0033));
    }

    final SliceRanking.Selection selection =
        SliceRanking.select(groups.reversed(), List.of(), new SliceBudget(300, 1200));

    assertThat(selection.nodes()).containsExactlyElementsOf(groups.subList(0, 300));
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void umLimitAbaixoDoInicioCortaGruposPorPagerank() {
    final SliceNode a = group("a", 0.45);
    final SliceNode b = group("b", 0.45);
    final SliceNode c = pkg("c", 0.10);
    final SliceEdge ca = edge(c, a, 1);
    final SliceEdge ab = edge(a, b, 1);

    final SliceRanking.Selection selection =
        SliceRanking.select(List.of(c, b, a), List.of(ca, ab), new SliceBudget(2, 1200));

    assertThat(selection.nodes()).containsExactly(a, b);
    assertThat(selection.edges()).containsExactly(ab);
    assertThat(selection.truncated()).isTrue();
  }

  @Test
  void umIdDeNoRepetidoERecusado() {
    final SliceNode a = type("A", 0.5);
    final SliceNode again = type("A", 0.1);

    assertThatThrownBy(
            () -> SliceRanking.select(List.of(a, again), List.of(), new SliceBudget(5, 5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void umParDeArestaRepetidoERecusado() {
    final SliceNode a = type("A", 0.5);
    final SliceNode b = type("B", 0.5);
    final SliceEdge first = edge(a, b, 1);
    final SliceEdge second = edge(a, b, 2);

    assertThatThrownBy(
            () -> SliceRanking.select(List.of(a, b), List.of(first, second), new SliceBudget(5, 5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void umaArestaComPontaForaDosNosERecusada() {
    final SliceNode a = type("A", 0.5);
    final SliceNode outside = type("Outside", 0.5);
    final SliceEdge dangling = edge(a, outside, 1);

    assertThatThrownBy(
            () -> SliceRanking.select(List.of(a), List.of(dangling), new SliceBudget(5, 5)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
