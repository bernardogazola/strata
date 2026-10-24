package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

final class StructureLimitsTest {

  private static final CodeElementId CORE = CodeElementId.ofPackage("br.pucpr.legacy.core");
  private static final CodeElementId DEFAULT_PACKAGE = CodeElementId.ofPackage("");
  private static final CodeElementId CATALOG = CodeElementId.ofType("br.pucpr.legacy.core.Catalog");
  private static final CodeElementId METHOD =
      CodeElementId.ofMethod("br.pucpr.legacy.core.Catalog", "find", List.of("java.lang.String"));
  private static final CodeElementId FILE =
      CodeElementId.ofFile("core/src/main/java/br/pucpr/legacy/core/Catalog.java");

  private static void assertRefused(ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
  }

  @Test
  void oNivelDePacotesSemLimitUsaOTetoDoNivel() {
    assertThat(SliceBudget.of(GraphLevel.PACKAGE, null)).isEqualTo(new SliceBudget(300, 1200));
  }

  @Test
  void oNivelDeTiposSemLimitUsaOTetoDoNivel() {
    assertThat(SliceBudget.of(GraphLevel.TYPE, null)).isEqualTo(new SliceBudget(1000, 5000));
  }

  @Test
  void umLimitAcimaDoTetoECortadoENuncaRecusado() {
    assertThat(SliceBudget.of(GraphLevel.TYPE, 5000)).isEqualTo(new SliceBudget(1000, 5000));
    assertThat(SliceBudget.of(GraphLevel.TYPE, 1001)).isEqualTo(new SliceBudget(1000, 5000));
    assertThat(SliceBudget.of(GraphLevel.TYPE, Integer.MAX_VALUE))
        .isEqualTo(new SliceBudget(1000, 5000));
    assertThat(SliceBudget.of(GraphLevel.PACKAGE, 1000)).isEqualTo(new SliceBudget(300, 1200));
    assertThat(SliceBudget.of(GraphLevel.PACKAGE, 301)).isEqualTo(new SliceBudget(300, 1200));
  }

  @Test
  void umLimitAbaixoDoTetoCortaOsNosENuncaAsArestas() {
    assertThat(SliceBudget.of(GraphLevel.TYPE, 1)).isEqualTo(new SliceBudget(1, 5000));
    assertThat(SliceBudget.of(GraphLevel.TYPE, 1000)).isEqualTo(new SliceBudget(1000, 5000));
    assertThat(SliceBudget.of(GraphLevel.PACKAGE, 2)).isEqualTo(new SliceBudget(2, 1200));
    assertThat(SliceBudget.of(GraphLevel.PACKAGE, 300)).isEqualTo(new SliceBudget(300, 1200));
  }

  @Test
  void umLimitAbaixoDeUmERecusado() {
    assertRefused(() -> SliceBudget.of(GraphLevel.TYPE, 0), Reason.LIMIT_NOT_POSITIVE);
    assertRefused(() -> SliceBudget.of(GraphLevel.PACKAGE, 0), Reason.LIMIT_NOT_POSITIVE);
    assertRefused(() -> SliceBudget.of(GraphLevel.PACKAGE, -1), Reason.LIMIT_NOT_POSITIVE);
    assertRefused(
        () -> SliceBudget.of(GraphLevel.TYPE, Integer.MIN_VALUE), Reason.LIMIT_NOT_POSITIVE);
  }

  @Test
  void aVizinhancaDeTipoNaoEServida() {
    assertRefused(
        () -> SliceBudget.of(GraphLevel.TYPE_NEIGHBORHOOD, null), Reason.LEVEL_NOT_SERVED);
    assertRefused(() -> SliceBudget.of(GraphLevel.TYPE_NEIGHBORHOOD, 10), Reason.LEVEL_NOT_SERVED);
  }

  @Test
  void umNivelNuloERecusado() {
    assertRefused(() -> SliceBudget.of(null, null), Reason.LEVEL_REQUIRED);
    assertRefused(() -> SliceBudget.of(null, 10), Reason.LEVEL_REQUIRED);
  }

  @Test
  void oNivelDePacotesNaoTemPai() {
    assertThatCode(() -> GraphSliceQuery.requireParent(GraphLevel.PACKAGE, null))
        .doesNotThrowAnyException();
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.PACKAGE, CORE), Reason.PARENT_NOT_ALLOWED);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.PACKAGE, DEFAULT_PACKAGE),
        Reason.PARENT_NOT_ALLOWED);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.PACKAGE, CATALOG),
        Reason.PARENT_NOT_ALLOWED);
  }

  @Test
  void oNivelDeTiposAceitaUmPkgInclusiveOPacotePadrao() {
    assertThatCode(() -> GraphSliceQuery.requireParent(GraphLevel.TYPE, CORE))
        .doesNotThrowAnyException();
    assertThatCode(() -> GraphSliceQuery.requireParent(GraphLevel.TYPE, DEFAULT_PACKAGE))
        .doesNotThrowAnyException();
  }

  @Test
  void oNivelDeTiposRecusaPaiAusenteOuDeOutroTipo() {
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE, null), Reason.PARENT_NOT_A_PACKAGE);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE, CATALOG), Reason.PARENT_NOT_A_PACKAGE);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE, METHOD), Reason.PARENT_NOT_A_PACKAGE);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE, FILE), Reason.PARENT_NOT_A_PACKAGE);
  }

  @Test
  void aVizinhancaDeTipoNaoEServidaQualquerQueSejaOPai() {
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE_NEIGHBORHOOD, null),
        Reason.LEVEL_NOT_SERVED);
    assertRefused(
        () -> GraphSliceQuery.requireParent(GraphLevel.TYPE_NEIGHBORHOOD, CATALOG),
        Reason.LEVEL_NOT_SERVED);
  }

  @Test
  void aPaginaDaEstruturaUsaOPadraoECortaNoMaximo() {
    assertThat(OutlineQuery.pageSize(null)).isEqualTo(200);
    assertThat(OutlineQuery.pageSize(9999)).isEqualTo(500);
    assertThat(OutlineQuery.pageSize(501)).isEqualTo(500);
    assertThat(OutlineQuery.pageSize(500)).isEqualTo(500);
    assertThat(OutlineQuery.pageSize(201)).isEqualTo(201);
    assertThat(OutlineQuery.pageSize(1)).isEqualTo(1);
  }

  @Test
  void umaPaginaAbaixoDeUmERecusada() {
    assertRefused(() -> OutlineQuery.pageSize(0), Reason.LIMIT_NOT_POSITIVE);
    assertRefused(() -> OutlineQuery.pageSize(-5), Reason.LIMIT_NOT_POSITIVE);
  }

  @Test
  void aEstruturaAceitaARaizUmPacoteEUmTipo() {
    assertThatCode(() -> OutlineQuery.requireParent(null)).doesNotThrowAnyException();
    assertThatCode(() -> OutlineQuery.requireParent(CORE)).doesNotThrowAnyException();
    assertThatCode(() -> OutlineQuery.requireParent(DEFAULT_PACKAGE)).doesNotThrowAnyException();
    assertThatCode(() -> OutlineQuery.requireParent(CATALOG)).doesNotThrowAnyException();
  }

  @Test
  void aEstruturaRecusaUmMetodoOuUmArquivoComoPai() {
    assertRefused(() -> OutlineQuery.requireParent(METHOD), Reason.OUTLINE_PARENT_KIND);
    assertRefused(() -> OutlineQuery.requireParent(FILE), Reason.OUTLINE_PARENT_KIND);
  }

  @Test
  void oTetoDeAchadosUsaOPadraoQueTambemEOMaximo() {
    assertThat(SearchQuery.resultLimit(null)).isEqualTo(50);
    assertThat(SearchQuery.resultLimit(99)).isEqualTo(50);
    assertThat(SearchQuery.resultLimit(51)).isEqualTo(50);
    assertThat(SearchQuery.resultLimit(50)).isEqualTo(50);
    assertThat(SearchQuery.resultLimit(1)).isEqualTo(1);
  }

  @Test
  void umTetoDeAchadosAbaixoDeUmERecusado() {
    assertRefused(() -> SearchQuery.resultLimit(0), Reason.LIMIT_NOT_POSITIVE);
    assertRefused(() -> SearchQuery.resultLimit(-1), Reason.LIMIT_NOT_POSITIVE);
  }

  @Test
  void semKindsABuscaPedeOsDoisTiposIndexados() {
    assertThat(SearchQuery.kinds(null))
        .containsExactlyInAnyOrder(ElementKind.PACKAGE, ElementKind.TYPE);
  }

  @Test
  void osKindsSaoAIntersecaoComOsDoisIndexados() {
    assertThat(SearchQuery.kinds(List.of(ElementKind.METHOD))).isEmpty();
    assertThat(SearchQuery.kinds(List.of())).isEmpty();
    assertThat(SearchQuery.kinds(List.of(ElementKind.TYPE, ElementKind.TYPE)))
        .containsExactly(ElementKind.TYPE);
    assertThat(
            SearchQuery.kinds(List.of(ElementKind.FILE, ElementKind.PACKAGE, ElementKind.METHOD)))
        .containsExactly(ElementKind.PACKAGE);
    assertThat(SearchQuery.kinds(List.of(ElementKind.TYPE, ElementKind.PACKAGE)))
        .containsExactlyInAnyOrder(ElementKind.PACKAGE, ElementKind.TYPE);
  }

  @Test
  void umKindNuloERecusado() {
    assertRefused(() -> SearchQuery.kinds(Arrays.asList((ElementKind) null)), Reason.KIND_REQUIRED);
    assertRefused(
        () -> SearchQuery.kinds(Arrays.asList(ElementKind.METHOD, null)), Reason.KIND_REQUIRED);
    assertRefused(
        () -> SearchQuery.kinds(Arrays.asList(ElementKind.PACKAGE, null, ElementKind.TYPE)),
        Reason.KIND_REQUIRED);
  }

  @Test
  void osKindsAplicadosSaoImutaveis() {
    final Set<ElementKind> kinds = SearchQuery.kinds(List.of(ElementKind.TYPE));

    assertThatThrownBy(() -> kinds.add(ElementKind.PACKAGE))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
