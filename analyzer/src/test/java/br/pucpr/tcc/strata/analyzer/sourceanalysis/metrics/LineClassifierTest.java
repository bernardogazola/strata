package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class LineClassifierTest {

  @Test
  void asSomasDePrefixoComecamEmZeroTemUmaPosicaoPorLinhaESobemDeZeroOuUm() {
    for (final String texto :
        List.of(
            "", "x", "\n", "x\n", "// c\nint x;\n\n/* a\n b */ y;\n", "\"\"\"\n  a\n\n\"\"\"")) {
      final int[] prefixo = LineClassifier.codeLinePrefix(texto.toCharArray());

      assertThat(prefixo[0]).as(texto).isZero();
      assertThat(prefixo).as(texto).hasSize(linhas(texto) + 1);
      for (int linha = 1; linha < prefixo.length; linha++) {
        assertThat(prefixo[linha] - prefixo[linha - 1]).as(texto).isBetween(0, 1);
      }
    }
  }

  @Test
  void crLfCrLfELfCrSaoTerminadoresEmSequencia() {
    assertThat(marcas("a\r\r\nb\n\rc")).isEqualTo("C.C.C");
    assertThat(marcas("\n\r\n\r")).isEqualTo("...");
    assertThat(marcas("a\r")).isEqualTo("C");
  }

  @Test
  void umComentarioDeLinhaTerminaNumCrSozinho() {
    assertThat(marcas("// c\rint x;\r")).isEqualTo(".C");
  }

  @Test
  void aBarraInvertidaDeUmaStringNaoConsomeOTerminador() {
    assertThat(marcas("s = \"a\\\n// c\n")).isEqualTo("C.");
    assertThat(marcas("s = \"a\\\r// c\r")).isEqualTo("C.");
    assertThat(marcas("c = '\\\r\n/* c */\r\n")).isEqualTo("C.");
  }

  @Test
  void aBarraInvertidaDeUmTextBlockNaoConsomeOTerminador() {
    assertThat(marcas("s = \"\"\"\n  a\\\r   \n  \"\"\";\n")).isEqualTo("CC.C");
  }

  @Test
  void aBarraInvertidaNoFimDoTextoNaoPassaDoFim() {
    assertThat(marcas("s = \"a\\")).isEqualTo("C");
    assertThat(marcas("c = '\\")).isEqualTo("C");
    assertThat(marcas("s = \"\"\"\n\\")).isEqualTo("CC");
  }

  @Test
  void umaStringOuUmCaractereSemFechoTerminaNaLinha() {
    assertThat(marcas("s = \"abc\n// c\nint x;\n")).isEqualTo("C.C");
    assertThat(marcas("c = 'x\n/* c */\n")).isEqualTo("C.");
  }

  @Test
  void umTextBlockSemFechoVaiAteOFimComOsMarcadoresInertes() {
    assertThat(marcas("s = \"\"\"\n*/\n// x\n\n\"\n")).isEqualTo("CCC.C");
  }

  @Test
  void umaLinhaDeEspacoFfEHtDentroDeUmTextBlockEBranca() {
    assertThat(marcas("s = \"\"\"\n\f\t \n\"\"\";\n")).isEqualTo("C.C");
  }

  @Test
  void oFimDoTextoNoMeioDeUmMarcadorNaoLeForaDoTexto() {
    assertThat(marcas("int x; /* a *")).isEqualTo("C");
    assertThat(marcas("/* a\n*")).isEqualTo("..");
    assertThat(marcas("/*")).isEqualTo(".");
    assertThat(marcas("x /")).isEqualTo("C");
    assertThat(marcas("/")).isEqualTo("C");
  }

  @Test
  void tresAspasNoFimDoTextoAbremUmTextBlockEDuasSaoUmaStringVazia() {
    assertThat(marcas("s = \"\"\"")).isEqualTo("C");
    assertThat(marcas("\"\"\"")).isEqualTo("C");
    assertThat(marcas("s = \"\"")).isEqualTo("C");
    assertThat(marcas("s = \"\"; // c\n// d\n")).isEqualTo("C.");
  }

  @Test
  void oArrayDePrefixosCresceAlemDaEstimativaInicial() {
    final int[] codigo = LineClassifier.codeLinePrefix("x\n".repeat(10_000).toCharArray());
    final int[] brancas = LineClassifier.codeLinePrefix("\n".repeat(10_000).toCharArray());

    assertThat(codigo).hasSize(10_001);
    for (int linha = 0; linha < codigo.length; linha++) {
      assertThat(codigo[linha]).isEqualTo(linha);
    }
    assertThat(brancas).hasSize(10_001).containsOnly(0);
  }

  @Test
  void umTextoNuloERecusado() {
    assertThatThrownBy(() -> LineClassifier.codeLinePrefix(null))
        .isInstanceOf(NullPointerException.class);
  }

  private static String marcas(String texto) {
    final int[] prefixo = LineClassifier.codeLinePrefix(texto.toCharArray());
    final StringBuilder marcas = new StringBuilder();
    for (int linha = 1; linha < prefixo.length; linha++) {
      marcas.append(prefixo[linha] > prefixo[linha - 1] ? 'C' : '.');
    }
    return marcas.toString();
  }

  private static int linhas(String texto) {
    final String normalizado = texto.replace("\r\n", "\n").replace('\r', '\n');
    if (normalizado.isEmpty()) {
      return 0;
    }
    final int terminadores = (int) normalizado.chars().filter(c -> c == '\n').count();
    return normalizado.endsWith("\n") ? terminadores : terminadores + 1;
  }
}
