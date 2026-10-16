package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SourceRootIdTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "api/src/main/java|sr_sqbycfms",
        "core/src/main/java|sr_3gwc7d6d",
        "core/src/test/java|sr_jc51sw4p",
        "core/target/generated-sources/annotations|sr_3sscth34",
        "src/main/java|sr_zbb227cf",
        "avaliação/src/main/java|sr_ma1rqqgk"
      })
  void derivaOMesmoIdQueOOraculoPython(String caminho, String esperado) {
    assertThat(SourceRootId.derive(caminho).value()).isEqualTo(esperado);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sr_ABCDEFGH", "sr_abcdefgi", "sr_abc", "rs_abcdefgh", "sr_abcdefghj"})
  void recusaValorForaDaGramaticaCrockfordMinuscula(String invalido) {
    assertThatThrownBy(() -> new SourceRootId(invalido))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
