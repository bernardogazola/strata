package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class GradleSettingsReaderTest {

  private static final long PRAZO_EM_SEGUNDOS = 5;

  private static String noTetoDeBuild(String fragmento) {
    return fragmento.repeat(AnalysisConstants.MAX_BUILD_FILE_BYTES / fragmento.length());
  }

  @Test
  void groovyComAspasSimplesEVirgulasNaOrdemDoDocumento() {
    final String settings =
        """
        rootProject.name = 'legacy'
        include 'core', 'api'
        include "web"
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .containsExactly("core", "api", "web");
  }

  @Test
  void groovySemParentesesAtravessaLinhaDepoisDaVirgula() {
    final String settings =
        """
        include 'a',
                'b',
                'c'
        include('d')
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .containsExactly("a", "b", "c", "d");
  }

  @Test
  void kotlinDslComListaEmVariasLinhasEVirgulaFinal() {
    final String settings =
        """
        rootProject.name = "platform"
        include(
            ":app",
            ":lib:util", // o utilitário
            \"""raw\""",
        )
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .containsExactly("app", "lib/util", "raw");
  }

  @Test
  void caminhoDeProjetoComDoisPontosViraPasta() {
    assertThat(GradleSettingsReader.includedDirectories("include ':services:billing', 'a:b'"))
        .containsExactly("services/billing", "a/b");
  }

  @Test
  void comentarioNaoIncluiNada() {
    final String settings =
        """
        // include 'line-comment'
        /* include 'block-comment'
           include 'still-in-block' */
        include 'real' // include 'trailing'
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings)).containsExactly("real");
  }

  @Test
  void marcadorDeComentarioDentroDeLiteralNaoAbreComentario() {
    final String settings =
        """
        rootProject.name = 'a/*b'
        include 'c'
        description = "d//e"
        include 'f'
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings)).containsExactly("c", "f");
  }

  @Test
  void includeFlatIncludeBuildEProjectDirNaoContam() {
    final String settings =
        """
        includeFlat 'sibling'
        includeBuild '../other-build'
        project(':moved').projectDir = file('elsewhere')
        include 'ok'
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings)).containsExactly("ok");
  }

  @Test
  void argumentoQueNaoELiteralEPuladoSemPerderOsVizinhos() {
    final String settings =
        """
        include(name, "b", call("x"), 'c')
        include(*arrayOf("spread"))
        include "templ$x"
        include 'literal$sign'
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .as("o template com $ só tem valor depois de avaliar o script; em aspa simples é literal")
        .containsExactly("b", "c", "literal$sign");
  }

  @Test
  void repeticaoNaoDuplicaEAOrdemEADaPrimeiraAparicao() {
    assertThat(GradleSettingsReader.includedDirectories("include 'b'\ninclude 'a', ':b'"))
        .containsExactly("b", "a");
  }

  @Test
  void literalSemFechoNaoViraModulo() {
    assertThat(GradleSettingsReader.includedDirectories("include 'open\ninclude 'next'"))
        .containsExactly("next");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {":a|a", "a|a", ":a:b:c|a/b/c", "a:b|a/b", "avaliação|avaliação"})
  void caminhoValidoDeProjetoViraAPastaPadrao(String projectPath, String directory) {
    assertThat(GradleSettingsReader.directoryOf(projectPath)).contains(directory);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ":", "::a", "a::b", "a:", ":..", "..", ".", "a:.", "a/b", "a\\b"})
  void caminhoQueNaoNomeiaSubprojetoEDescartado(String projectPath) {
    assertThat(GradleSettingsReader.directoryOf(projectPath)).isEmpty();
  }

  @Test
  void includeDentroDeListaSemFechoEncerraALista() {
    final String settings =
        """
        include('a', 'b'
        include 'c'
        include('d',
        include('e')
        """;

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .as(
            "'b' e o argumento depois de 'd,' não têm fim conhecido e ficam de fora; os include"
                + " seguintes ainda são lidos")
        .containsExactly("a", "c", "d", "e");
  }

  @Test
  @Timeout(value = PRAZO_EM_SEGUNDOS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void umMegaDeIncludeSemFechoELinearENaoDaModulo() {
    final String settings = noTetoDeBuild("include(");

    assertThat(GradleSettingsReader.includedDirectories(settings)).isEmpty();
  }

  @Test
  @Timeout(value = PRAZO_EM_SEGUNDOS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void umMegaDeIncludeComLiteralSemFechoELinearEDaUmModuloSo() {
    final String settings = noTetoDeBuild("include(\"a\",");

    assertThat(GradleSettingsReader.includedDirectories(settings))
        .as("cada lista termina no include seguinte com o 'a' já fechado por vírgula")
        .containsExactly("a");
  }
}
