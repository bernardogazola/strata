package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import java.util.List;
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
  void oEscopoDeUmaUnidadeAplicaAMesmaRegra() {
    assertThat(INDEX.scope("x", Set.of("c")).apply("Other")).isEqualTo("c.Other");
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
