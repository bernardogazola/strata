package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SimpleNameIndexTest {

  private static final SourceRootId ROOT = SourceRootId.derive("src/main/java");

  private static final SimpleNameIndex INDEX =
      SimpleNameIndex.of(
          List.of(
              analyzed("src/main/java/a/Api.java", "a", "Api"),
              analyzed("src/main/java/b/Helper.java", "b", "Helper"),
              analyzed("src/main/java/c/Api.java", "c", "Api", "Other"),
              analyzed("src/main/java/Root.java", "", "Root"),
              new SourceFile(
                  "src/main/java/d/Big.java",
                  ROOT,
                  SourceFileStatus.SKIPPED_TOO_LARGE,
                  null,
                  null)));

  @Test
  void umNomeNuDoProprioPacoteGanhaOPacote() {
    assertThat(INDEX.canonicalize("Helper", "b", Set.of())).isEqualTo("b.Helper");
  }

  @Test
  void umNomeNuDeUmUnicoImportSobDemandaGanhaOPacoteImportado() {
    assertThat(INDEX.canonicalize("Other", "x", Set.of("c"))).isEqualTo("c.Other");
  }

  @Test
  void umNomeQueDoisImportsSobDemandaOferecemFicaComoEsta() {
    assertThat(INDEX.canonicalize("Api", "x", Set.of("a", "c"))).isEqualTo("Api");
  }

  @Test
  void oProprioPacoteVenceUmImportSobDemanda() {
    assertThat(INDEX.canonicalize("Api", "a", Set.of("c"))).isEqualTo("a.Api");
  }

  @Test
  void umNomeExternoFicaComoEsta() {
    assertThat(INDEX.canonicalize("ParseTreeListener", "x", Set.of("a", "org.antlr")))
        .isEqualTo("ParseTreeListener");
    assertThat(INDEX.canonicalize("org.antlr.Lexer", "x", Set.of("a")))
        .isEqualTo("org.antlr.Lexer");
  }

  @Test
  void umTipoDoPacotePadraoNaoEVisivelDeUmPacoteComNome() {
    assertThat(INDEX.canonicalize("Root", "x", Set.of())).isEqualTo("Root");
    assertThat(INDEX.canonicalize("Root", "", Set.of())).isEqualTo("Root");
    assertThat(INDEX.canonicalize("Root.Inner", "x", Set.of())).isEqualTo("Root.Inner");
    assertThat(INDEX.canonicalize("Root.Inner", "", Set.of())).isEqualTo("Root$Inner");
  }

  @Test
  void umArquivoSemDeclaracoesNaoEntraNoIndice() {
    assertThat(INDEX.canonicalize("Big", "d", Set.of())).isEqualTo("Big");
  }

  @Test
  void umNomeNuAninhadoResolveOTopoEMantemOResto() {
    assertThat(INDEX.canonicalize("Other$Inner$Deep", "x", Set.of("c")))
        .isEqualTo("c.Other$Inner$Deep");
  }

  @Test
  void umNomeComPontosGanhaDolarDepoisDoMaiorPrefixoInterno() {
    assertThat(INDEX.canonicalize("a.Api.Inner.Deep", "x", Set.of())).isEqualTo("a.Api$Inner$Deep");
    assertThat(INDEX.canonicalize("c.Other.Inner", "x", Set.of())).isEqualTo("c.Other$Inner");
  }

  @Test
  void umNomeComPontosSemPrefixoInternoResolveOPrimeiroSegmentoComoNomeNu() {
    assertThat(INDEX.canonicalize("Other.Inner", "x", Set.of("c"))).isEqualTo("c.Other$Inner");
    assertThat(INDEX.canonicalize("Helper.Inner.Deep", "b", Set.of()))
        .isEqualTo("b.Helper$Inner$Deep");
    assertThat(INDEX.canonicalize("Api.Inner", "a", Set.of("c"))).isEqualTo("a.Api$Inner");
  }

  @Test
  void umPrimeiroSegmentoAmbiguoOuSemTipoInternoFicaComoEsta() {
    assertThat(INDEX.canonicalize("Api.Inner", "x", Set.of("a", "c"))).isEqualTo("Api.Inner");
    assertThat(INDEX.canonicalize("Missing.Inner", "x", Set.of("c"))).isEqualTo("Missing.Inner");
  }

  @Test
  void oPalpiteDoProprioPacoteCedeAUmImportSobDemanda() {
    assertThat(INDEX.canonicalize("x.Other", "x", Set.of("c"))).isEqualTo("c.Other");
    assertThat(INDEX.canonicalize("x.Other$Inner", "x", Set.of("c"))).isEqualTo("c.Other$Inner");
    assertThat(INDEX.canonicalize("x.Missing", "x", Set.of("c"))).isEqualTo("x.Missing");
    assertThat(INDEX.canonicalize("x.Api", "x", Set.of("a", "c"))).isEqualTo("x.Api");
  }

  @Test
  void umNomeQueOSpoonResolveuNaoMuda() {
    for (final String resolved :
        List.of(
            "java.util.List",
            "java.util.Map$Entry",
            "a.Api",
            "a.Api$Inner",
            "b.Helper",
            "c.Other$Inner",
            "a.b.Single",
            "int")) {
      assertThat(INDEX.canonicalize(resolved, "b", Set.of("a", "c")))
          .as(resolved)
          .isEqualTo(resolved);
    }
    assertThat(INDEX.canonicalize("b.Helper$Inner", "b", Set.of("c"))).isEqualTo("b.Helper$Inner");
  }

  @Test
  void umNomeEscritoSimplesCedeAoTipoDoProprioPacote() {
    final SimpleNameIndex.Scope scope = INDEX.scope("b", Set.of("c"), Map.of());

    assertThat(scope.canonical("java.lang.Helper", true)).isEqualTo("b.Helper");
    assertThat(scope.canonical("c.Helper", true)).isEqualTo("b.Helper");
    assertThat(scope.canonical("java.lang.Helper", false)).isEqualTo("java.lang.Helper");
    assertThat(INDEX.scope("x", Set.of(), Map.of()).canonical("java.lang.Helper", true))
        .isEqualTo("java.lang.Helper");
  }

  @Test
  void umImportDeTipoUnicoVenceOProprioPacote() {
    final SimpleNameIndex.Scope internal = INDEX.scope("a", Set.of(), Map.of("Api", "c.Api"));
    final SimpleNameIndex.Scope external = INDEX.scope("a", Set.of(), Map.of("Api", "org.x.Api"));
    final SimpleNameIndex.Scope nested = INDEX.scope("a", Set.of(), Map.of("Api", "c.Other.Api"));

    assertThat(internal.canonical("java.lang.Api", true)).isEqualTo("c.Api");
    assertThat(internal.canonical("c.Api", true)).isEqualTo("c.Api");
    assertThat(external.canonical("org.x.Api", true)).isEqualTo("org.x.Api");
    assertThat(external.canonical("java.lang.Api", true)).isEqualTo("java.lang.Api");
    assertThat(nested.canonical("java.lang.Api", true)).isEqualTo("java.lang.Api");
  }

  @Test
  void oPrimeiroSegmentoDeUmNomeComPontosCedeAntesAoImportDeTipoUnico() {
    assertThat(INDEX.scope("x", Set.of(), Map.of("Api", "a.Api")).canonical("Api.Inner", false))
        .isEqualTo("a.Api$Inner");
    assertThat(INDEX.scope("c", Set.of(), Map.of("Api", "a.Api")).canonical("Api.Inner", false))
        .as("vence o c.Api do próprio pacote")
        .isEqualTo("a.Api$Inner");
    assertThat(INDEX.scope("x", Set.of("c"), Map.of("Api", "a.Api")).canonical("Api.Inner", false))
        .as("vence o c.Api do import sob demanda")
        .isEqualTo("a.Api$Inner");
    assertThat(INDEX.scope("x", Set.of(), Map.of("Api", "org.x.Api")).canonical("Api.Inner", false))
        .isEqualTo("org.x.Api$Inner");
    assertThat(
            INDEX
                .scope("x", Set.of(), Map.of("Inner", "a.Api.Inner"))
                .canonical("Inner.Deep", false))
        .isEqualTo("a.Api$Inner$Deep");
  }

  @Test
  void oEscopoDeUmaUnidadeAplicaAMesmaRegra() {
    assertThat(INDEX.scope("x", Set.of("c"), Map.of()).canonical("Other", false))
        .isEqualTo("c.Other");
    assertThat(INDEX.scope("x", Set.of("c"), Map.of()).canonical("x.Other", true))
        .isEqualTo("c.Other");
  }

  private static SourceFile analyzed(String path, String packageName, String... types) {
    return new SourceFile(
        path,
        ROOT,
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations(packageName, List.of(types)));
  }
}
