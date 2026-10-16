package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.BuildDescription.DeclaredSourceDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class BuildSystemDetectorTest {

  private static final String EMPTY_POM = "<project/>";
  private static final RepositoryLimits PRODUCTION = RepositoryLimits.PRODUCTION;

  @TempDir private Path temp;

  private Path real;

  @BeforeEach
  void caminhoReal() throws IOException {
    real = temp.toRealPath();
  }

  private BuildDescription detectar() throws IOException {
    return new BuildSystemDetector(PRODUCTION).detect(real, TestRepositories.inventoryOf(real));
  }

  private static RepositoryLimits comTetoDeBuild(int maxBuildFileBytes) {
    return new RepositoryLimits(
        PRODUCTION.maxEnumeratedFiles(),
        PRODUCTION.maxDirectoryDepth(),
        PRODUCTION.maxPathBytes(),
        maxBuildFileBytes,
        PRODUCTION.maxGitConfigBytes(),
        PRODUCTION.maxWarnings());
  }

  private static String pomDeTamanho(int size) {
    final String open = "<project><!--";
    final String close = "--></project>";
    return open + "x".repeat(size - open.length() - close.length()) + close;
  }

  static Stream<Arguments> precedencia() {
    return Stream.of(
        Arguments.of(List.of("pom.xml", "settings.gradle"), BuildSystem.MAVEN),
        Arguments.of(List.of("pom.xml", "build.xml", "BUILD.bazel"), BuildSystem.MAVEN),
        Arguments.of(List.of("settings.gradle.kts"), BuildSystem.GRADLE),
        Arguments.of(List.of("build.gradle.kts"), BuildSystem.GRADLE),
        Arguments.of(List.of("build.gradle"), BuildSystem.GRADLE),
        Arguments.of(List.of("settings.gradle"), BuildSystem.GRADLE),
        Arguments.of(List.of("build.xml", "build.gradle"), BuildSystem.GRADLE),
        Arguments.of(List.of("build.xml"), BuildSystem.ANT),
        Arguments.of(List.of("build.xml", "build.sbt"), BuildSystem.ANT),
        Arguments.of(List.of("BUILD.bazel"), BuildSystem.UNKNOWN),
        Arguments.of(List.of("WORKSPACE"), BuildSystem.UNKNOWN),
        Arguments.of(List.of("WORKSPACE.bazel"), BuildSystem.UNKNOWN),
        Arguments.of(List.of("MODULE.bazel"), BuildSystem.UNKNOWN),
        Arguments.of(List.of("build.sbt"), BuildSystem.UNKNOWN),
        Arguments.of(List.of(), BuildSystem.NONE),
        Arguments.of(List.of("core/pom.xml", "lib/build.gradle", "x/build.xml"), BuildSystem.NONE));
  }

  @ParameterizedTest
  @MethodSource("precedencia")
  void soAsEntradasDaRaizClassificamNaPrecedenciaMavenGradleAntUnknownNone(
      List<String> arquivos, BuildSystem esperado) throws IOException {
    for (final String arquivo : arquivos) {
      TestRepositories.write(real, arquivo, arquivo.endsWith("pom.xml") ? EMPTY_POM : "// x\n");
    }

    final BuildDescription build = detectar();

    assertThat(build.buildSystem()).isEqualTo(esperado);
    assertThat(build.declaredModules()).isEmpty();
    assertThat(build.moduleDirectories()).containsExactly("");
    assertThat(build.declaredSourceDirectories()).isEmpty();
  }

  @Test
  void pomMalformadoEUnknownMesmoComSettingsGradleAoLado() throws IOException {
    TestRepositories.write(real, "pom.xml", "<project><modules><module>core</module>");
    TestRepositories.write(real, "settings.gradle", "include 'core'");

    final BuildDescription build = detectar();

    assertThat(build.buildSystem())
        .as("o POM ilegível ainda diz que o projeto é Maven; cair para o Gradle mentiria")
        .isEqualTo(BuildSystem.UNKNOWN);
    assertThat(build.declaredModules()).isEmpty();
    assertThat(build.moduleDirectories()).containsExactly("");
  }

  @Test
  void pomComOutraRaizEUnknown() throws IOException {
    TestRepositories.write(
        real, "pom.xml", "<settings><modules><module>a</module></modules></settings>");

    assertThat(detectar().buildSystem()).isEqualTo(BuildSystem.UNKNOWN);
  }

  @Test
  void pomComXxeEUnknownENaoLeOArquivoLocal() throws IOException {
    final Path segredo = Files.writeString(real.resolve("segredo.txt"), "SEGREDO");
    TestRepositories.write(
        real,
        "pom.xml",
        "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM \"file://"
            + segredo.toString().replace('\\', '/')
            + "\">]><project><modules><module>&x;</module></modules></project>");

    final BuildDescription build = detectar();

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.UNKNOWN);
    assertThat(build.declaredModules()).isEmpty();
  }

  @Test
  void pomNoTetoExatoELidoEUmByteAlemEUnknown() throws IOException {
    final int teto = 256;
    TestRepositories.write(real, "pom.xml", pomDeTamanho(teto));
    final RepositoryInventory noTeto = TestRepositories.inventoryOf(real);
    TestRepositories.write(real, "pom.xml", pomDeTamanho(teto + 1));
    final RepositoryInventory alemDoTeto = TestRepositories.inventoryOf(real);
    final BuildSystemDetector detector = new BuildSystemDetector(comTetoDeBuild(teto));

    assertThat(detector.detect(real, alemDoTeto).buildSystem()).isEqualTo(BuildSystem.UNKNOWN);
    assertThat(detector.detect(real, noTeto).buildSystem())
        .as("o inventário diz 256 bytes, mas o arquivo cresceu: a leitura limitada é que decide")
        .isEqualTo(BuildSystem.UNKNOWN);
    TestRepositories.write(real, "pom.xml", pomDeTamanho(teto));
    assertThat(detector.detect(real, noTeto).buildSystem()).isEqualTo(BuildSystem.MAVEN);
  }

  @Test
  void pomAcimaDoTetoDeProducaoEUnknown() throws IOException {
    TestRepositories.write(real, "pom.xml", pomDeTamanho(PRODUCTION.maxBuildFileBytes() + 1));

    assertThat(detectar().buildSystem()).isEqualTo(BuildSystem.UNKNOWN);
  }

  @Test
  void pomQueESymlinkEUnknownENuncaEAberto() throws IOException {
    final Path alvo = TestRepositories.write(temp.resolve("fora"), "pom.xml", EMPTY_POM);
    TestRepositories.symlink(real.resolve("repo/pom.xml"), alvo);
    final Path repo = real.resolve("repo");

    final BuildDescription build =
        new BuildSystemDetector(PRODUCTION).detect(repo, TestRepositories.inventoryOf(repo));

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.UNKNOWN);
  }

  @Test
  void pomQueEDiretorioEUnknown() throws IOException {
    Files.createDirectories(real.resolve("pom.xml"));
    final RepositoryInventory inventario =
        new RepositoryInventory(1, List.of(), List.of(new BuildFileEntry("pom.xml", false, 96)));

    assertThat(new BuildSystemDetector(PRODUCTION).detect(real, inventario).buildSystem())
        .isEqualTo(BuildSystem.UNKNOWN);
  }

  @Test
  void reatorFechaOsModulosTransitivamenteSemSairDaRaiz() throws IOException {
    TestRepositories.write(
        real,
        "pom.xml",
        """
        <project>
          <modules>
            <module>core</module>
            <module>api</module>
            <module>../outside</module>
            <module>/abs/x</module>
            <module>C:\\win</module>
            <module>alt/custom-pom.xml</module>
            <module>core</module>
          </modules>
          <profiles><profile><modules>
            <module>extra</module>
            <module>api</module>
          </modules></profile></profiles>
        </project>
        """);
    TestRepositories.write(
        real,
        "core/pom.xml",
        "<project><modules><module>sub</module><module>..</module></modules></project>");
    TestRepositories.write(
        real,
        "core/sub/pom.xml",
        "<project><modules><module>deep\\leaf</module></modules></project>");
    TestRepositories.write(real, "core/sub/deep/leaf/pom.xml", EMPTY_POM);
    TestRepositories.write(
        real, "api/pom.xml", "<project><modules><module>../../escape</module></modules></project>");
    TestRepositories.write(
        real, "alt/custom-pom.xml", "<project><modules><module>never</module></modules></project>");

    final BuildDescription build = detectar();

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.MAVEN);
    assertThat(build.declaredModules())
        .as("os do topo, depois os de perfil, sem os que saem da raiz e sem repetição")
        .containsExactly("core", "api", "alt/custom-pom.xml", "extra");
    assertThat(build.moduleDirectories())
        .as(
            "'..' volta ao POM já visitado sem laço; a barra invertida do valor é separador; o POM"
                + " de nome próprio dá a pasta, mas não é lido: 'alt/never' não aparece")
        .containsExactly("", "alt", "api", "core", "core/sub", "core/sub/deep/leaf", "extra");
  }

  @Test
  void pastasDeCodigoDeclaradasSaoResolvidasContraOPomDoModulo() throws IOException {
    TestRepositories.write(
        real,
        "pom.xml",
        """
        <project>
          <modules><module>core</module><module>web</module></modules>
          <build>
            <sourceDirectory>${project.basedir}/src/java</sourceDirectory>
            <testSourceDirectory>${basedir}/test</testSourceDirectory>
          </build>
        </project>
        """);
    TestRepositories.write(
        real,
        "core/pom.xml",
        """
        <project><build>
          <sourceDirectory>../shared/src</sourceDirectory>
          <testSourceDirectory>${env.TESTS}/t</testSourceDirectory>
        </build></project>
        """);
    TestRepositories.write(
        real,
        "web/pom.xml",
        """
        <project><build>
          <sourceDirectory>/etc</sourceDirectory>
          <testSourceDirectory>../../fora</testSourceDirectory>
        </build></project>
        """);

    final BuildDescription build = detectar();

    assertThat(build.declaredSourceDirectories())
        .as("propriedade desconhecida, caminho absoluto e fuga da raiz ficam de fora")
        .containsExactly(
            new DeclaredSourceDirectory("src/java", SourceRootKind.MAIN),
            new DeclaredSourceDirectory("test", SourceRootKind.TEST),
            new DeclaredSourceDirectory("shared/src", SourceRootKind.MAIN));
  }

  @Test
  void recursaoDoReatorParaNoTetoDeProfundidade() throws IOException {
    final int niveis = AnalysisConstants.MAX_DIRECTORY_DEPTH + 6;
    final String filho = "<project><modules><module>m</module></modules></project>";
    TestRepositories.write(real, "pom.xml", filho);
    for (int nivel = 1; nivel <= niveis; nivel++) {
      TestRepositories.write(real, "m/".repeat(nivel) + "pom.xml", filho);
    }

    final BuildDescription build = detectar();

    assertThat(build.moduleDirectories())
        .as("a raiz e um módulo por POM lido, do nível 0 ao teto")
        .hasSize(AnalysisConstants.MAX_DIRECTORY_DEPTH + 2)
        .contains("m/".repeat(AnalysisConstants.MAX_DIRECTORY_DEPTH) + "m")
        .doesNotContain("m/".repeat(AnalysisConstants.MAX_DIRECTORY_DEPTH + 1) + "m");
  }

  @Test
  void gradleTiraOsModulosDosIncludesDoSettings() throws IOException {
    TestRepositories.write(
        real, "settings.gradle", "rootProject.name = 'x'\ninclude 'web', ':services:billing'\n");
    TestRepositories.write(real, "build.gradle", "// nada\n");

    final BuildDescription build = detectar();

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.GRADLE);
    assertThat(build.declaredModules()).containsExactly("web", "services/billing");
    assertThat(build.moduleDirectories()).containsExactly("", "services/billing", "web");
    assertThat(build.declaredSourceDirectories()).isEmpty();
  }

  @Test
  void gradleKotlinDslELido() throws IOException {
    TestRepositories.write(real, "settings.gradle.kts", "include(\":app\", \":lib:util\")\n");

    final BuildDescription build = detectar();

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.GRADLE);
    assertThat(build.declaredModules()).containsExactly("app", "lib/util");
  }

  @Test
  void settingsGradleQueESymlinkAindaEGradleMasSemIncludes() throws IOException {
    final Path alvo =
        TestRepositories.write(temp.resolve("fora"), "settings.gradle", "include 'x'");
    TestRepositories.symlink(real.resolve("repo/settings.gradle"), alvo);
    final Path repo = real.resolve("repo");

    final BuildDescription build =
        new BuildSystemDetector(PRODUCTION).detect(repo, TestRepositories.inventoryOf(repo));

    assertThat(build.buildSystem()).isEqualTo(BuildSystem.GRADLE);
    assertThat(build.declaredModules()).isEmpty();
    assertThat(build.moduleDirectories()).containsExactly("");
  }
}
