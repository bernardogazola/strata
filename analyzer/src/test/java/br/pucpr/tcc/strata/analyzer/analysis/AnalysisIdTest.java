package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AnalysisIdTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "an_00000000000000000000000000",
        "an_7ZZZZZZZZZZZZZZZZZZZZZZZZZ",
        "an_0123456789ABCDEFGHJKMNPQRS",
        "an_0TVWXYZ0000000000000000000"
      })
  void aceitaOsIdsDaGramatica(String texto) {
    assertThat(AnalysisId.parse(texto).value()).isEqualTo(texto);
    assertThat(new AnalysisId(texto)).isEqualTo(AnalysisId.parse(texto));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "an_",
        "AN_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "an-01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "req_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1WX",
        "an_01jq8z9k3m4n5p6q7r8s9t0v1w",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1I",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1L",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1O",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1U",
        "an_80000000000000000000000000",
        "an_Z0000000000000000000000000",
        " an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W",
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W\n"
      })
  void recusaComAMensagemFixaQueNaoEcoaOTexto(String texto) {
    assertThatThrownBy(() -> AnalysisId.parse(texto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisId.MALFORMED);
    assertThatThrownBy(() -> new AnalysisId(texto))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisId.MALFORMED);
  }

  @Test
  void parseDeNuloERecusaENaoNullPointer() {
    assertThatThrownBy(() -> AnalysisId.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisId.MALFORMED);
  }

  @Test
  void aMensagemNaoTrazOValorRecebido() {
    assertThatThrownBy(() -> AnalysisId.parse("an_/etc/passwd"))
        .hasMessageNotContaining("/etc/passwd");
  }
}
