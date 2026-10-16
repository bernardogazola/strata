package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SyntaxPreParser.PreParse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SourceCharsetsTest {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  @Test
  void asciiEUtf8() {
    final byte[] content = "class A { int x; }\n".getBytes(StandardCharsets.US_ASCII);

    assertThat(SourceCharsets.detect(content)).isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  void umArquivoVazioEUtf8() {
    assertThat(SourceCharsets.detect(new byte[0])).isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  void utf8ValidoDeVariosBytesEUtf8() {
    final byte[] content = bytes('a', 0xC3, 0xA7, 0xC3, 0xA3, 'o');

    assertThat(SourceCharsets.detect(content)).isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  void oBomUtf8EUtf8ValidoEADecisaoSobreEleFicaComOPreParse() {
    final byte[] content = bytes(0xEF, 0xBB, 0xBF, 'c', 'l', 'a', 's', 's');

    assertThat(SourceCharsets.detect(content)).isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  void bytesIso88591SaoWindows1252() {
    final byte[] content = bytes('a', 0xE7, 0xE3, 'o');

    assertThat(SourceCharsets.detect(content)).isEqualTo(WINDOWS_1252);
  }

  @Test
  void umaSequenciaDeVariosBytesCortadaNoFimEWindows1252() {
    final byte[] content = bytes('p', 0xC3);

    assertThat(SourceCharsets.detect(content)).isEqualTo(WINDOWS_1252);
  }

  @Test
  void umaFormaLongaEWindows1252() {
    final byte[] content = bytes('a', 0xC0, 0xAF, 'b');

    assertThat(SourceCharsets.detect(content)).isEqualTo(WINDOWS_1252);
  }

  @Test
  void windows1252DecodificaAsAspasCurvasEOEuroQueIso88591NaoTem() {
    final byte[] content = bytes('/', '/', ' ', 0x93, 'x', 0x94, ' ', 0x80, '5');

    final Charset charset = SourceCharsets.detect(content);

    assertThat(charset).isEqualTo(WINDOWS_1252);
    assertThat(new String(content, charset)).isEqualTo("// “x” €5");
  }

  @Test
  void umIdentificadorLatin1DecodificadoPelaRegraNaoEErroDeSintaxe() {
    final byte[] content = latin1Funcao();

    final Charset charset = SourceCharsets.detect(content);
    final String decoded = new String(content, charset);
    final PreParse preParse = SyntaxPreParser.parse("A.java", decoded.toCharArray());

    assertThat(decoded).isEqualTo("class A { int função; }");
    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.declarations().topLevelTypes()).containsExactly("A");
  }

  @Test
  void osMesmosBytesLidosComoUtf8ViramErroDeSintaxe() {
    final byte[] content = latin1Funcao();

    final String decoded = new String(content, StandardCharsets.UTF_8);
    final PreParse preParse = SyntaxPreParser.parse("A.java", decoded.toCharArray());

    assertThat(decoded).contains("�");
    assertThat(preParse.syntaxError()).isTrue();
  }

  private static byte[] latin1Funcao() {
    return bytes(
        'c', 'l', 'a', 's', 's', ' ', 'A', ' ', '{', ' ', 'i', 'n', 't', ' ', 'f', 'u', 'n', 0xE7,
        0xE3, 'o', ';', ' ', '}');
  }

  private static byte[] bytes(int... values) {
    final byte[] result = new byte[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = (byte) values[index];
    }
    return result;
  }
}
