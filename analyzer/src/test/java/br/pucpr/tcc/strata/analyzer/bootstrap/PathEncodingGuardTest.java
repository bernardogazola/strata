package br.pucpr.tcc.strata.analyzer.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class PathEncodingGuardTest {

  @Test
  void linuxSemLocaleEhRecusado() {
    assertThat(PathEncodingGuard.refuses("Linux", "ANSI_X3.4-1968")).isTrue();
  }

  @Test
  void umLocaleQueExisteMasNaoEhUtf8TambemEhRecusado() {
    assertThat(PathEncodingGuard.refuses("Linux", "ISO-8859-1")).isTrue();
  }

  @Test
  void propriedadeAusenteNaoEhProvaDeUtf8() {
    assertThat(PathEncodingGuard.refuses("Linux", null)).isTrue();
    assertThat(PathEncodingGuard.refuses(null, null)).isTrue();
  }

  @Test
  void utf8EmQualquerGrafiaPassaForaDoWindows() {
    assertThat(PathEncodingGuard.refuses("Linux", "UTF-8")).isFalse();
    assertThat(PathEncodingGuard.refuses("Linux", "utf8")).isFalse();
    assertThat(PathEncodingGuard.refuses("Mac OS X", "UTF-8")).isFalse();
  }

  @Test
  void noWindowsACodePageAnsiNuncaDisparaARecusa() {
    assertThat(PathEncodingGuard.refuses("Windows 11", "Cp1252")).isFalse();
    assertThat(PathEncodingGuard.refuses("Windows Server 2022", "MS936")).isFalse();
  }

  @Test
  void oCodigoDeSaidaEhOExConfigDoSysexitsEDistintoDosOutros() {
    assertThat(AnalysisConstants.EXIT_PLATFORM_ENCODING)
        .isEqualTo(78)
        .isNotEqualTo(AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED)
        .isNotEqualTo(AnalysisConstants.EXIT_OK);
  }
}
