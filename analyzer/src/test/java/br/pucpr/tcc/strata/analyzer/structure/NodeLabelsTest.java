package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class NodeLabelsTest {

  private static final String MULTI_MODULE_CPP = "br.pucpr.legacy";
  private static final String MATH_BOLD_A = Character.toString(0x1D400);

  @Test
  void oCppDoMultiModuleEBrPucprLegacy() {
    final String l =
        NodeLabels.segmentAlignedPrefix("br.pucpr.legacy.api", "br.pucpr.legacy.core.spi");

    assertThat(l).isEqualTo("br.pucpr.legacy");
    assertThat(NodeLabels.commonProperPrefix(l, false)).isEqualTo(MULTI_MODULE_CPP);
  }

  @Test
  void oCppDoSimplePackagesEBrPorqueBrPucprEPacote() {
    final String l = NodeLabels.segmentAlignedPrefix("br.pucpr", "br.pucpr.orders");

    assertThat(l).isEqualTo("br.pucpr");
    assertThat(NodeLabels.commonProperPrefix(l, true)).isEqualTo("br");
  }

  @Test
  void oPrefixoComumEAlinhadoASegmento() {
    assertThat(NodeLabels.segmentAlignedPrefix("br.pucpr.legacy.api", "br.pucpr.legacy.apx"))
        .isEqualTo("br.pucpr.legacy");
    assertThat(NodeLabels.segmentAlignedPrefix("a.b", "a.bc")).isEqualTo("a");
    assertThat(NodeLabels.segmentAlignedPrefix("a.c", "ab.c")).isEmpty();
    assertThat(NodeLabels.segmentAlignedPrefix("a", "b")).isEmpty();
  }

  @Test
  void osExemplosDoJavadocDoPrefixoComum() {
    assertThat(NodeLabels.segmentAlignedPrefix("a.b.c", "a.b.d")).isEqualTo("a.b");
    assertThat(NodeLabels.segmentAlignedPrefix("a.b", "a.b.c")).isEqualTo("a.b");
    assertThat(NodeLabels.segmentAlignedPrefix("x.y", "z.y")).isEmpty();
  }

  @Test
  void umPacoteSoTemOProprioUltimoSegmentoPorRotulo() {
    final String l = NodeLabels.segmentAlignedPrefix("br.pucpr.app", "br.pucpr.app");
    final String cpp = NodeLabels.commonProperPrefix(l, true);

    assertThat(l).isEqualTo("br.pucpr.app");
    assertThat(cpp).isEqualTo("br.pucpr");
    assertThat(NodeLabels.packageLabel("br.pucpr.app", cpp)).isEqualTo("app");
    assertThat(NodeLabels.commonProperPrefix("app", true)).isEmpty();
    assertThat(NodeLabels.packageLabel("app", "")).isEqualTo("app");
  }

  @Test
  void umQVazioDaCppVazio() {
    assertThat(NodeLabels.commonProperPrefix("", false)).isEmpty();
    assertThat(NodeLabels.commonProperPrefix("", true)).isEmpty();
    assertThat(NodeLabels.packageLabel("", "")).isEmpty();
  }

  @Test
  void osRotulosDosPacotesDoMultiModule() {
    assertThat(NodeLabels.packageLabel("br.pucpr.legacy.api", MULTI_MODULE_CPP)).isEqualTo("api");
    assertThat(NodeLabels.packageLabel("br.pucpr.legacy.core", MULTI_MODULE_CPP)).isEqualTo("core");
    assertThat(NodeLabels.packageLabel("br.pucpr.legacy.core.gen", MULTI_MODULE_CPP))
        .isEqualTo("core.gen");
    assertThat(NodeLabels.packageLabel("br.pucpr.legacy.core.spi", MULTI_MODULE_CPP))
        .isEqualTo("core.spi");
  }

  @Test
  void osRotulosDosPacotesDoSimplePackages() {
    assertThat(NodeLabels.packageLabel("br.pucpr", "br")).isEqualTo("pucpr");
    assertThat(NodeLabels.packageLabel("br.pucpr.orders", "br")).isEqualTo("pucpr.orders");
    assertThat(NodeLabels.packageLabel("", "br")).isEmpty();
  }

  @Test
  void umPacoteIgualAoPrefixoComumAindaTemRotulo() {
    final String l =
        NodeLabels.segmentAlignedPrefix(
            "org.apache.commons.lang3", "org.apache.commons.lang3.text.translate");
    final String cpp = NodeLabels.commonProperPrefix(l, true);

    assertThat(l).isEqualTo("org.apache.commons.lang3");
    assertThat(cpp).isEqualTo("org.apache.commons");
    assertThat(NodeLabels.packageLabel("org.apache.commons.lang3", cpp)).isEqualTo("lang3");
    assertThat(NodeLabels.packageLabel("org.apache.commons.lang3.builder", cpp))
        .isEqualTo("lang3.builder");
    assertThat(NodeLabels.packageLabel("org.apache.commons.lang3.text.translate", cpp))
        .isEqualTo("lang3.text.translate");
  }

  @Test
  void comCppVazioORotuloEONomeInteiro() {
    assertThat(NodeLabels.packageLabel("a.p000", "")).isEqualTo("a.p000");
    assertThat(NodeLabels.packageLabel("c", "")).isEqualTo("c");
  }

  @Test
  void oRotuloDeUmPrefixoTiraOCppESeuPonto() {
    assertThat(NodeLabels.prefixLabel("br.pucpr.legacy.core", MULTI_MODULE_CPP)).isEqualTo("core");
    assertThat(NodeLabels.prefixLabel("org.elasticsearch.action", "org.elasticsearch"))
        .isEqualTo("action");
    assertThat(NodeLabels.prefixLabel("a", "")).isEqualTo("a");
    assertThat(NodeLabels.prefixLabel("b", "")).isEqualTo("b");
  }

  @Test
  void umPrefixoQueFicariaVazioTemOPrefixoInteiroPorRotulo() {
    assertThat(NodeLabels.prefixLabel("br.pucpr.legacy", MULTI_MODULE_CPP))
        .isEqualTo("br.pucpr.legacy");
  }

  @Test
  void oRotuloDeUmTipoERelativoAoPacote() {
    assertThat(NodeLabels.typeLabel("br.pucpr.orders.Order.Builder", "br.pucpr.orders"))
        .isEqualTo("Order.Builder");
    assertThat(NodeLabels.typeLabel("br.pucpr.orders.Order", "br.pucpr.orders")).isEqualTo("Order");
    assertThat(NodeLabels.typeLabel("br.pucpr.legacy.core.Catalog", "br.pucpr.legacy.core"))
        .isEqualTo("Catalog");
  }

  @Test
  void umTipoDoPacotePadraoTemONomeQualificadoPorRotulo() {
    assertThat(NodeLabels.typeLabel("Bootstrap", "")).isEqualTo("Bootstrap");
    assertThat(NodeLabels.typeLabel("Outer.Inner", "")).isEqualTo("Outer.Inner");
  }

  @Test
  void umTipoNaoAsciiPerdeSoOPacote() {
    final String pacote = "br.pucpr.pedidos.avaliação";

    assertThat(NodeLabels.typeLabel(pacote + ".Avaliação", pacote)).isEqualTo("Avaliação");
  }

  @Test
  void umRotuloDeAte512PontosDeCodigoFicaInteiro() {
    assertThat(NodeLabels.clamp("")).isEmpty();
    assertThat(NodeLabels.clamp("x".repeat(512))).isEqualTo("x".repeat(512));
    assertThat(NodeLabels.clamp(MATH_BOLD_A.repeat(512))).isEqualTo(MATH_BOLD_A.repeat(512));
  }

  @Test
  void umRotuloAcimaDe512PontosDeCodigoECortado() {
    assertThat(NodeLabels.clamp("x".repeat(513))).isEqualTo("x".repeat(512));
    assertThat(NodeLabels.clamp(MATH_BOLD_A.repeat(513))).isEqualTo(MATH_BOLD_A.repeat(512));
  }

  @Test
  void oCorteNuncaParteUmParSubstituto() {
    final String label = "a".repeat(511) + MATH_BOLD_A + "z";
    final String clamped = NodeLabels.clamp(label);

    assertThat(clamped).isEqualTo("a".repeat(511) + MATH_BOLD_A);
    assertThat(clamped).hasSize(513);
    assertThat(clamped.codePointCount(0, clamped.length())).isEqualTo(512);
  }

  @Test
  void osTresRotulosSaemCortados() {
    assertThat(NodeLabels.packageLabel("x." + "y".repeat(600), "x")).isEqualTo("y".repeat(512));
    assertThat(NodeLabels.prefixLabel("x." + "y".repeat(600), "x")).isEqualTo("y".repeat(512));
    assertThat(NodeLabels.typeLabel("p." + "a".repeat(511) + MATH_BOLD_A + "b", "p"))
        .isEqualTo("a".repeat(511) + MATH_BOLD_A);
  }
}
