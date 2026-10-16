package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.NestingScreen.Nesting;
import org.junit.jupiter.api.Test;

final class NestingScreenTest {

  private static final int LIMIT = AnalysisConstants.MAX_SOURCE_NESTING_DEPTH;

  @Test
  void aProfundidadeSomaChavesParentesesEColchetes() {
    final Nesting nesting = measure("class A { int f() { return a[(1)]; } }");

    assertThat(nesting.bracketDepth()).isEqualTo(4);
    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void colchetesLadoALadoNaoAprofundam() {
    final Nesting nesting = measure("{}{}(){}[]");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
  }

  @Test
  void oitentaMilChavesAbertasPassamDoTeto() {
    final Nesting nesting = measure("class A { void f() { " + "{".repeat(80_000) + " int x = 1 ");

    assertThat(nesting.bracketDepth()).isEqualTo(80_002);
    assertThat(nesting.tooDeep()).isTrue();
  }

  @Test
  void novecentosNiveisLegitimosNaoPassamDoTeto() {
    final Nesting nesting =
        measure("class A { void f() { " + "{".repeat(900) + "}".repeat(900) + " } }");

    assertThat(nesting.bracketDepth()).isEqualTo(902);
    assertThat(nesting.tooDeep()).isFalse();
  }

  @Test
  void exatamenteNoTetoPassaEUmAcimaNao() {
    assertThat(measure("(".repeat(LIMIT)).tooDeep()).isFalse();
    assertThat(measure("(".repeat(LIMIT + 1)).tooDeep()).isTrue();
    assertThat(measure("x -> ".repeat(LIMIT)).tooDeep()).isFalse();
    assertThat(measure("x -> ".repeat(LIMIT + 1)).tooDeep()).isTrue();
  }

  @Test
  void umFechamentoSemAberturaNaoDesceAbaixoDeZero() {
    final Nesting nesting = measure("}}} ))) {");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
  }

  @Test
  void umaCadeiaDeLambdasContaCadaSeta() {
    final Nesting nesting = measure("class A { Object f() { return x -> x -> x -> 1; } }");

    assertThat(nesting.arrowChain()).isEqualTo(3);
  }

  @Test
  void trintaMilLambdasAninhadasPassamDoTetoSemAbrirColchete() {
    final Nesting nesting =
        measure("class D { Object f() { return " + "x -> ".repeat(30_000) + "1; } }");

    assertThat(nesting.arrowChain()).isEqualTo(30_000);
    assertThat(nesting.bracketDepth()).isEqualTo(2);
    assertThat(nesting.tooDeep()).isTrue();
  }

  @Test
  void lambdasEmArgumentosSeparadosPorVirgulaContamUmaCada() {
    final Nesting nesting = measure("foo(a -> 1, b -> 2, c -> 3);");

    assertThat(nesting.arrowChain()).isEqualTo(1);
  }

  @Test
  void sessentaMilLambdasLadoALadoNaoSaoCadeia() {
    final StringBuilder source = new StringBuilder("class D { void f() { ");
    for (int index = 0; index < 60_000; index++) {
      source.append("Runnable r").append(index).append(" = () -> {}; ");
    }
    final Nesting nesting = measure(source.append("} }").toString());

    assertThat(nesting.arrowChain()).isEqualTo(1);
    assertThat(nesting.bracketDepth()).isEqualTo(3);
    assertThat(nesting.tooDeep()).isFalse();
  }

  @Test
  void aLambdaComCorpoEmBlocoAninhaPelaChaveENaoPelaSeta() {
    final Nesting nesting = measure("f = x -> { return y -> { return 1; }; };");

    assertThat(nesting.arrowChain()).isEqualTo(1);
    assertThat(nesting.bracketDepth()).isEqualTo(2);
  }

  @Test
  void osCasosDeUmSwitchComCorpoEmBlocoNaoSaoCadeia() {
    final Nesting nesting =
        measure("switch (k) { case 1 -> { a(); } case 2 -> { b(); } default -> { c(); } }");

    assertThat(nesting.arrowChain()).isEqualTo(1);
  }

  @Test
  void aCadeiaContinuaDepoisDeUmParenteseQueFecha() {
    final Nesting nesting = measure("f = (a, b) -> (c) -> 1;");

    assertThat(nesting.arrowChain()).isEqualTo(2);
  }

  @Test
  void menosMenosMaiorNaoESeta() {
    final Nesting nesting = measure("while (i-->0 && j-->0 && k-->0) {}");

    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void colchetesESetasEmComentariosSaoIgnorados() {
    final Nesting nesting =
        measure(
            "class A {\n  // {{{ ((( -> -> ->\n  /* [[[ { -> -> */\n  /** {@code {{{} -> */\n}\n");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void colchetesESetasEmLiteraisDeTextoECaractereSaoIgnorados() {
    final Nesting nesting =
        measure(
            "class A { String s = \"{{ -> (( \\\" [[ -> \"; char c = '{'; char q = '\\''; char p ="
                + " '('; }");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void colchetesESetasEmBlocosDeTextoSaoIgnorados() {
    final Nesting nesting =
        measure(
            "class A {\n  String t = \"\"\"\n    {{{ -> -> \"\" \\\"\"\" (((\n    [[[\n    \"\"\";\n}\n");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void umLiteralSemFechamentoTerminaNoFimDaLinha() {
    final Nesting nesting = measure("String s = \"aberto\n{ {");

    assertThat(nesting.bracketDepth()).isEqualTo(2);
  }

  @Test
  void umComentarioDeBlocoSemFechamentoVaiAteOFim() {
    final Nesting nesting = measure("{ /* {{{{ ->");

    assertThat(nesting.bracketDepth()).isEqualTo(1);
    assertThat(nesting.arrowChain()).isZero();
  }

  @Test
  void umEscapeUnicodeContaComoOCaractereQueEleProduz() {
    final Nesting nesting = measure("\\u007B\\uu007b\\u007B\\u002D\\u003E");

    assertThat(nesting.bracketDepth()).isEqualTo(3);
    assertThat(nesting.arrowChain()).isEqualTo(1);
  }

  @Test
  void umaBarraEscapadaNaoAbreEscapeUnicode() {
    final Nesting nesting = measure("\\\\u007B\\\\u007B");

    assertThat(nesting.bracketDepth()).isZero();
  }

  @Test
  void umEscapeUnicodeQueFechaOLiteralFechaOLiteral() {
    final Nesting nesting = measure("s = \"abc\\u0022 + f({x});");

    assertThat(nesting.bracketDepth()).isEqualTo(2);
  }

  private static Nesting measure(String source) {
    return NestingScreen.measure(source.toCharArray());
  }
}
