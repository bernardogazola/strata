package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import org.junit.jupiter.api.Test;

final class SearchTermTest {

  private static final String GRINNING_FACE = Character.toString(0x1F600);
  private static final String COMBINING_ACUTE = Character.toString(0x0301);
  private static final String COMBINING_TILDE = Character.toString(0x0303);
  private static final String COMBINING_CEDILLA = Character.toString(0x0327);
  private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);
  private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);
  private static final String LIGATURE_FI = Character.toString(0xFB01);
  private static final String FULLWIDTH_FULL_STOP = Character.toString(0xFF0E);
  private static final String HIGH_SURROGATE = String.valueOf((char) 0xD83D);
  private static final String LOW_SURROGATE = String.valueOf((char) 0xDE00);

  private static String codePoints(int... codePoints) {
    return new String(codePoints, 0, codePoints.length);
  }

  private static void assertRefused(String query, Reason reason) {
    assertThatThrownBy(() -> SearchTerm.of(query))
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
  }

  @Test
  void centoEVinteEOitoPontosDeCodigoCabem() {
    final String query = "a".repeat(128);

    assertThat(SearchTerm.of(query)).contains(new SearchTerm(query));
  }

  @Test
  void centoEVinteENovePontosDeCodigoSaoRecusados() {
    assertRefused("a".repeat(129), Reason.QUERY_TOO_LONG);
  }

  @Test
  void oTetoContaPontosDeCodigoENaoUnidadesUtf16() {
    final String fits = GRINNING_FACE.repeat(128);

    assertThat(fits).hasSize(256);
    assertThat(SearchTerm.of(fits)).contains(new SearchTerm(fits));
    assertRefused(GRINNING_FACE.repeat(129), Reason.QUERY_TOO_LONG);
  }

  @Test
  void oTetoEDaConsultaEnaoDaDobra() {
    assertThat(SearchTerm.of(LIGATURE_FI.repeat(128))).contains(new SearchTerm("fi".repeat(128)));
  }

  @Test
  void oTetoVemAntesDoSubstitutoSolitario() {
    assertRefused(HIGH_SURROGATE + "a".repeat(128), Reason.QUERY_TOO_LONG);
  }

  @Test
  void umaConsultaNulaERecusada() {
    assertRefused(null, Reason.QUERY_REQUIRED);
  }

  @Test
  void umaConsultaCujaDobraEVaziaEVazia() {
    assertThat(SearchTerm.of("")).isEmpty();
    assertThat(SearchTerm.of(COMBINING_ACUTE)).isEmpty();
    assertThat(SearchTerm.of(ZERO_WIDTH_SPACE)).isEmpty();
    assertThat(SearchTerm.of(COMBINING_ACUTE + ZERO_WIDTH_SPACE + RIGHT_TO_LEFT_OVERRIDE))
        .isEmpty();
  }

  @Test
  void umSubstitutoSolitarioDeixaAConsultaVazia() {
    assertThat(SearchTerm.of("a" + HIGH_SURROGATE + "b")).isEmpty();
    assertThat(SearchTerm.of(LOW_SURROGATE)).isEmpty();
    assertThat(SearchTerm.of("order" + HIGH_SURROGATE)).isEmpty();
    assertThat(SearchTerm.of(LOW_SURROGATE + "order")).isEmpty();
  }

  @Test
  void acaoDobraParaAcaoEmNfcEEmNfd() {
    final String nfc = codePoints('A', 0xE7, 0xE3, 'o');
    final String nfd = "Ac" + COMBINING_CEDILLA + "a" + COMBINING_TILDE + "o";
    final String upper = codePoints('A', 0xC7, 0xC3, 'O');
    assertThat(nfc).hasSize(4);
    assertThat(nfd).hasSize(6);

    assertThat(SearchTerm.of(nfc)).contains(new SearchTerm("acao"));
    assertThat(SearchTerm.of(nfd)).contains(new SearchTerm("acao"));
    assertThat(SearchTerm.of(upper)).contains(new SearchTerm("acao"));
  }

  @Test
  void aCaixaEALarguraCheiaDobram() {
    assertThat(SearchTerm.of("OrderService")).contains(new SearchTerm("orderservice"));
    assertThat(SearchTerm.of(codePoints(0xFF2F, 0xFF52, 0xFF44, 0xFF45, 0xFF52)))
        .contains(new SearchTerm("order"));
  }

  @Test
  void umInvisivelNoMeioDaConsultaCai() {
    assertThat(SearchTerm.of("ord" + ZERO_WIDTH_SPACE + "er")).contains(new SearchTerm("order"));
  }

  @Test
  void oJavaNuncaAparaAConsulta() {
    assertThat(SearchTerm.of(" a")).contains(new SearchTerm(" a"));
    assertThat(SearchTerm.of("a ")).contains(new SearchTerm("a "));
    assertThat(SearchTerm.of(" A ")).contains(new SearchTerm(" a "));
    assertThat(SearchTerm.of(" ")).contains(new SearchTerm(" "));
  }

  @Test
  void umaConsultaComPontoViraOUltimoSegmentoDobrado() {
    assertThat(SearchTerm.of("core.spi")).contains(new SearchTerm("spi"));
    assertThat(SearchTerm.of("Order.Builder")).contains(new SearchTerm("builder"));
    assertThat(SearchTerm.of("br.pucpr.legacy.core.Catalog")).contains(new SearchTerm("catalog"));
    assertThat(SearchTerm.of(".spi")).contains(new SearchTerm("spi"));
    assertThat(SearchTerm.of("core. Spi")).contains(new SearchTerm(" spi"));
  }

  @Test
  void umUltimoSegmentoVazioDeixaAConsultaVazia() {
    assertThat(SearchTerm.of("a.")).isEmpty();
    assertThat(SearchTerm.of(".")).isEmpty();
    assertThat(SearchTerm.of("..")).isEmpty();
    assertThat(SearchTerm.of("a.b.")).isEmpty();
    assertThat(SearchTerm.of("core." + COMBINING_ACUTE)).isEmpty();
  }

  @Test
  void oTetoEOSubstitutoSolitarioSaoDaConsultaInteira() {
    assertThat(SearchTerm.of("a".repeat(126) + ".b")).contains(new SearchTerm("b"));
    assertRefused("a".repeat(127) + ".b", Reason.QUERY_TOO_LONG);
    assertThat(SearchTerm.of(HIGH_SURROGATE + ".spi")).isEmpty();
  }

  @Test
  void oPontoDeLarguraCheiaNaoCortaEViraPontoNaDobra() {
    assertThat(SearchTerm.of("core" + FULLWIDTH_FULL_STOP + "spi"))
        .contains(new SearchTerm("core.spi"));
  }

  @Test
  void oTermoVazioNaoExiste() {
    assertThatThrownBy(() -> new SearchTerm("")).isInstanceOf(IllegalArgumentException.class);
  }
}
