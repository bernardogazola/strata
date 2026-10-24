package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class OutlineCursorCodecTest {

  private static final Pattern WIRE_CURSOR = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

  private static void assertMalformed(String wire) {
    assertThatThrownBy(() -> OutlineCursor.decode(wire))
        .as("decode(%s)", wire)
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            refused -> assertThat(refused.reason()).isEqualTo(Reason.CURSOR_MALFORMED));
  }

  @Test
  void aCodificacaoEADoOraculo() {
    assertThat(new OutlineCursor(1).encode()).isEqualTo("AQAAAAAAAAAB");
    assertThat(new OutlineCursor(1L << 31).encode()).isEqualTo("AQAAAACAAAAA");
    assertThat(new OutlineCursor(Long.MAX_VALUE).encode()).isEqualTo("AX__________");
    assertThat(new OutlineCursor(255).encode()).isEqualTo("AQAAAAAAAAD_");
    assertThat(new OutlineCursor(62).encode()).isEqualTo("AQAAAAAAAAA-");
  }

  @Test
  void todoCursorTemDozeCaracteresDoAlfabetoUrlSemPreenchimento() {
    for (final long pk : new long[] {1, 62, 255, 256, 1L << 31, Long.MAX_VALUE}) {
      final String wire = new OutlineCursor(pk).encode();

      assertThat(wire).as("pk %d", pk).hasSize(12).doesNotContain("=").matches(WIRE_CURSOR);
    }
  }

  @Test
  void decodificarOQueSeCodificouDevolveAMesmaChave() {
    for (final long pk : new long[] {1, 62, 255, 256, 1L << 31, Long.MAX_VALUE}) {
      assertThat(OutlineCursor.decode(new OutlineCursor(pk).encode()))
          .as("pk %d", pk)
          .isEqualTo(new OutlineCursor(pk));
    }
  }

  @Test
  void aDecodificacaoLeOsTextosDoOraculo() {
    assertThat(OutlineCursor.decode("AQAAAAAAAAAB")).isEqualTo(new OutlineCursor(1));
    assertThat(OutlineCursor.decode("AQAAAACAAAAA")).isEqualTo(new OutlineCursor(1L << 31));
    assertThat(OutlineCursor.decode("AX__________")).isEqualTo(new OutlineCursor(Long.MAX_VALUE));
    assertThat(OutlineCursor.decode("AQAAAAAAAAEA")).isEqualTo(new OutlineCursor(256));
  }

  @Test
  void oTextoVazioEMalformado() {
    assertMalformed("");
  }

  @Test
  void umCursorComPreenchimentoEMalformado() {
    assertMalformed("AQAAAAAAAAE=");
    assertMalformed("AQAAAAAAAAAB==");
  }

  @Test
  void umCursorDeOutroTamanhoEMalformado() {
    assertMalformed("AQAAAAAAAAA");
    assertMalformed("AQAAAAAAAAABA");
    assertMalformed("AQAAAAAAAAAAAQ");
    assertMalformed("AQAAAAAAAAAAAAAB");
  }

  @Test
  void umCursorDeOutraVersaoEMalformado() {
    assertMalformed("AAAAAAAAAAAB");
    assertMalformed("AgAAAAAAAAAB");
  }

  @Test
  void umCursorForaDoAlfabetoUrlEMalformado() {
    for (final String wire :
        List.of(
            "AQAAAAAAAAA+",
            "AQAAAAAAAAA/",
            "AQAAAAAAAAA*",
            " AQAAAAAAAAA",
            "AQAAAAAAAA\nB",
            "AQAAAAAAAAAé")) {
      assertMalformed(wire);
    }
  }

  @Test
  void umCursorComChaveAbaixoDeUmEMalformado() {
    assertMalformed("AQAAAAAAAAAA");
    assertMalformed("AYAAAAAAAAAA");
  }

  @Test
  void umCursorAcimaDoTetoEMalformado() {
    assertMalformed("A".repeat(257));
    assertMalformed("A".repeat(256));
  }
}
