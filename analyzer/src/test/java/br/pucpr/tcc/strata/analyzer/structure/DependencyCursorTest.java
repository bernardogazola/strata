package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.structure.DependencyDirection.IN;
import static br.pucpr.tcc.strata.analyzer.structure.DependencyDirection.OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DependencyCursorTest {

  private static final Pattern WIRE_CURSOR = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

  private static void assertMalformed(String wire) {
    assertThatThrownBy(() -> DependencyCursor.decode(wire))
        .as("decode(%s)", wire)
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            refused -> {
              assertThat(refused.reason()).isEqualTo(Reason.CURSOR_MALFORMED);
              assertThat(refused.detail()).isEqualTo("o cursor está malformado");
            });
  }

  @Test
  void aCodificacaoEADoOraculo() {
    assertThat(new DependencyCursor(IN, 1, 1).encode()).isEqualTo("AgAAAAAAAAAAAQAAAAAAAAAB");
    assertThat(new DependencyCursor(OUT, 1, 1).encode()).isEqualTo("AgEAAAAAAAAAAQAAAAAAAAAB");
    assertThat(new DependencyCursor(IN, 1L << 31, 255).encode())
        .isEqualTo("AgAAAAAAgAAAAAAAAAAAAAD_");
    assertThat(new DependencyCursor(IN, 4096, 1).encode()).isEqualTo("AgAAAAAAAAAQAAAAAAAAAAAB");
    assertThat(new DependencyCursor(OUT, Long.MAX_VALUE, Long.MAX_VALUE).encode())
        .isEqualTo("AgF__________3__________");
    assertThat(new DependencyCursor(OUT, 62, 62).encode()).isEqualTo("AgEAAAAAAAAAPgAAAAAAAAA-");
    assertThat(new DependencyCursor(OUT, 62, 63).encode()).isEqualTo("AgEAAAAAAAAAPgAAAAAAAAA_");
  }

  @Test
  void todoCursorTemVinteEQuatroCaracteresDoAlfabetoUrlSemPreenchimento() {
    for (final DependencyDirection sentido : DependencyDirection.values()) {
      for (final long pk : new long[] {1, 62, 63, 255, 256, 1L << 31, Long.MAX_VALUE}) {
        final String wire = new DependencyCursor(sentido, pk, Long.MAX_VALUE - pk + 1).encode();

        assertThat(wire)
            .as("%s %d", sentido, pk)
            .hasSize(24)
            .doesNotContain("=")
            .matches(WIRE_CURSOR);
      }
    }
  }

  @Test
  void decodificarOQueSeCodificouDevolveOMesmoCursor() {
    for (final DependencyDirection sentido : DependencyDirection.values()) {
      for (final long pk : new long[] {1, 62, 63, 255, 256, 1L << 31, Long.MAX_VALUE}) {
        final DependencyCursor cursor = new DependencyCursor(sentido, pk, 7);

        assertThat(DependencyCursor.decode(cursor.encode())).as("%s", cursor).isEqualTo(cursor);
      }
    }
  }

  @Test
  void aDecodificacaoLeOsTextosDoOraculo() {
    assertThat(DependencyCursor.decode("AgAAAAAAAAAAAQAAAAAAAAAB"))
        .isEqualTo(new DependencyCursor(IN, 1, 1));
    assertThat(DependencyCursor.decode("AgEAAAAAAAAAAQAAAAAAAAAB"))
        .isEqualTo(new DependencyCursor(OUT, 1, 1));
    assertThat(DependencyCursor.decode("AgAAAAAAgAAAAAAAAAAAAAD_"))
        .isEqualTo(new DependencyCursor(IN, 1L << 31, 255));
    assertThat(DependencyCursor.decode("AgAAAAAAAAAQAAAAAAAAAAAB"))
        .isEqualTo(new DependencyCursor(IN, 4096, 1));
    assertThat(DependencyCursor.decode("AgEAAAAAAAAAPgAAAAAAAAA-"))
        .isEqualTo(new DependencyCursor(OUT, 62, 62));
  }

  @Test
  void oTextoVazioEMalformado() {
    assertMalformed("");
  }

  @Test
  void umCursorDeOutroTamanhoEMalformado() {
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAA");
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAAA");
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAAABAA");
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAAABA");
  }

  @Test
  void oCursorDaEstruturaEMalformadoAqui() {
    assertMalformed("AQAAAAAAAAAq");
    assertMalformed(new OutlineCursor(42).encode());
    assertMalformed("AQAAAAAAAAAAAQAAAAAAAAAB");
  }

  @Test
  void umCursorDeOutraVersaoEMalformado() {
    assertMalformed("AAEAAAAAAAAAAQAAAAAAAAAB");
    assertMalformed("AQAAAAAAAAAAAQAAAAAAAAAB");
    assertMalformed("AwAAAAAAAAAAAQAAAAAAAAAB");
  }

  @Test
  void umByteDeSentidoForaDeZeroOuUmEMalformado() {
    assertMalformed("AgIAAAAAAAAAAQAAAAAAAAAB");
    assertMalformed("Av8AAAAAAAAAAQAAAAAAAAAB");
  }

  @Test
  void umaChaveAbaixoDeUmEMalformada() {
    assertMalformed("AgAAAAAAAAAAAAAAAAAAAAAB");
    assertMalformed("AgEAAAAAAAAAAQAAAAAAAAAA");
    assertMalformed("AgD__________wAAAAAAAAAB");
    assertMalformed("AgEAAAAAAAAAAYAAAAAAAAAA");
  }

  @Test
  void umCursorForaDoAlfabetoUrlEMalformado() {
    assertMalformed("AgEAAAAAAAAAPgAAAAAAAAA+");
    assertMalformed("AgEAAAAAAAAAPgAAAAAAAAA/");
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAA==");
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAAA=");
    for (final String sujo :
        List.of(
            "AgAAAAAAAAAAAQAAAAAAAA*B",
            "AgAAAAAAAAAAAQAAAAAAAA B",
            "AgAAAAAAAAAAAQAAAAAAAA\nB",
            "AgAAAAAAAAAAAQAAAAAAAA" + Character.toString(0xE9) + "B")) {
      assertMalformed(sujo);
    }
  }

  @Test
  void acimaDoTetoEMalformado() {
    assertMalformed("A".repeat(AnalysisConstants.MAX_CURSOR_CHARS + 1));
    assertMalformed("AgAAAAAAAAAAAQAAAAAAAAAB".repeat(43_691));
  }
}
