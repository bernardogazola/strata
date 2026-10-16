package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CanonicalJsonTest {

  @Test
  void caractereForaDoBmpSaiCruComoNoJsonStringify() {
    final String rotulo = "café😀‮";

    final String emitido =
        new String(
            new CanonicalJson()
                .toBytes(Envelope.ok("req_01JQ8ZF1B2C3D4E5F6G7H8J9K0", Map.of("label", rotulo))),
            StandardCharsets.UTF_8);

    assertThat(emitido)
        .as("JSON.stringify({label: 'café😀‮'}) escreve o emoji e o U+202E crus")
        .contains("\"label\":\"" + rotulo + "\"")
        .doesNotContain("\\u");
  }

  @Test
  void controleC0AindaSaiComoEscapeEPorIssoOCorteNaOrigemORemove() {
    final String emitido =
        new String(
            new CanonicalJson()
                .toBytes(
                    Envelope.ok("req_01JQ8ZF1B2C3D4E5F6G7H8J9K0", Map.of("label", "a\u0001b"))),
            StandardCharsets.UTF_8);

    assertThat(emitido)
        .as("o único escape que resta é o de controle C0, que o UntrustedText tira na origem")
        .contains("\\u0001");
  }
}
