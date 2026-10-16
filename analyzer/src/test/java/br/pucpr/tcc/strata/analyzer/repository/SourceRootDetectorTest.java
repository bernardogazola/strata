package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class SourceRootDetectorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir private Path temp;

  private Path real;

  private record Raiz(String path, SourceRootKind kind, int javaFileCount) {}

  static Stream<String> fixturesComGolden() throws IOException {
    try (Stream<Path> children = Files.list(TestRepositories.corpusRoot())) {
      return children
          .filter(child -> Files.isRegularFile(child.resolve("expected.json")))
          .map(child -> child.getFileName().toString())
          .sorted()
          .toList()
          .stream();
    }
  }

  private static ProjectLayout detectar(String fixture) throws IOException {
    final Path root = TestRepositories.corpus(fixture);
    return new ProjectLayoutDetector().detect(root, TestRepositories.inventoryOf(root));
  }

  private static List<Raiz> raizesDoGolden(JsonNode golden) {
    final List<Raiz> raizes = new ArrayList<>();
    for (final JsonNode raiz : golden.get("sourceRoots")) {
      raizes.add(
          new Raiz(
              raiz.get("path").asText(),
              SourceRootKind.valueOf(raiz.get("kind").asText()),
              raiz.get("javaFileCount").asInt()));
    }
    raizes.sort(Comparator.comparing(Raiz::path));
    return raizes;
  }

  @Test
  void oCorpusFoiLidoEOsDozeGoldensEstaoLa() throws IOException {
    assertThat(fixturesComGolden().toList())
        .as("sem esta asserção o teste parametrizado passaria por vacuidade sobre lista vazia")
        .hasSize(12)
        .contains("multi-module", "missing-classpath", "cyclic", "simple-packages");
  }

  @ParameterizedTest
  @MethodSource("fixturesComGolden")
  void reproduzOGoldenDeRaizesSistemaDeBuildEModulos(String fixture) throws IOException {
    final JsonNode golden =
        JSON.readTree(TestRepositories.corpus(fixture).resolve("expected.json").toFile());
    final Set<String> claims = new HashSet<>();
    golden.get("claims").forEach(claim -> claims.add(claim.asText()));

    final ProjectLayout layout = detectar(fixture);

    if (claims.contains("sourceRoots")) {
      assertThat(layout.sourceRoots())
          .extracting(raiz -> new Raiz(raiz.relativePath(), raiz.kind(), raiz.javaFileCount()))
          .as(fixture + ": raízes, na ordem por caminho")
          .containsExactlyElementsOf(raizesDoGolden(golden));
    }
    if (claims.contains("buildSystem")) {
      assertThat(layout.buildSystem())
          .as(fixture + ": sistema de build")
          .isEqualTo(BuildSystem.valueOf(golden.get("buildSystem").asText()));
    }
    if (claims.contains("declaredModules")) {
      final List<String> modulos = new ArrayList<>();
      golden.get("declaredModules").forEach(modulo -> modulos.add(modulo.asText()));
      assertThat(layout.declaredModules())
          .as(fixture + ": módulos na ordem do documento")
          .containsExactlyElementsOf(modulos);
    }
  }

  @Test
  void multiModuleDescobreAsQuatroRaizesDeTresNaturezasSemExecutarOBuild() throws IOException {
    final ProjectLayout layout = detectar("multi-module");

    assertThat(layout.buildSystem())
        .as("pom.xml E settings.gradle na raiz: o POM de reator vence")
        .isEqualTo(BuildSystem.MAVEN);
    assertThat(layout.declaredModules()).containsExactly("core", "api");
    assertThat(layout.sourceRoots())
        .containsExactly(
            new SourceRoot(
                new SourceRootId("sr_sqbycfms"), "api/src/main/java", SourceRootKind.MAIN, 2),
            new SourceRoot(
                new SourceRootId("sr_3gwc7d6d"), "core/src/main/java", SourceRootKind.MAIN, 6),
            new SourceRoot(
                new SourceRootId("sr_jc51sw4p"), "core/src/test/java", SourceRootKind.TEST, 1),
            new SourceRoot(
                new SourceRootId("sr_3sscth34"),
                "core/target/generated-sources/annotations",
                SourceRootKind.GENERATED,
                1));
  }

  @BeforeEach
  void caminhoReal() throws IOException {
    real = temp.toRealPath();
  }

  private ProjectLayout detectarAqui() throws IOException {
    return new ProjectLayoutDetector().detect(real, TestRepositories.inventoryOf(real));
  }

  private static List<Raiz> raizes(ProjectLayout layout) {
    return layout.sourceRoots().stream()
        .map(raiz -> new Raiz(raiz.relativePath(), raiz.kind(), raiz.javaFileCount()))
        .toList();
  }

  private void escrever(String caminho, String conteudo) throws IOException {
    TestRepositories.write(real, caminho, conteudo);
  }

  @Test
  void reatorMavenDescartaOProjetoDeTesteDeIntegracaoEOMoldeDeArquetipo() throws IOException {
    escrever("pom.xml", "<project><packaging>maven-plugin</packaging></project>");
    escrever("src/main/java/br/x/Mojo.java", "package br.x; class Mojo {}");
    escrever("src/test/java/br/x/MojoTest.java", "package br.x; class MojoTest {}");
    escrever("src/it/smoke/pom.xml", "<project/>");
    escrever("src/it/smoke/src/main/java/App.java", "class App {}");
    escrever(
        "src/main/resources/archetype-resources/src/main/java/Template.java", "class Template {}");

    final ProjectLayout comReator = detectarAqui();
    Files.delete(real.resolve("pom.xml"));
    final ProjectLayout semReator = detectarAqui();

    assertThat(raizes(comReator))
        .as(
            "só o módulo \"\" é do reator: src/it e o arquétipo têm src/main/java, mas não são módulo")
        .containsExactly(
            new Raiz("src/main/java", SourceRootKind.MAIN, 1),
            new Raiz("src/test/java", SourceRootKind.TEST, 1));
    assertThat(raizes(semReator))
        .as("controle: sem POM, a convenção sozinha acha as quatro")
        .hasSize(4);
  }

  @Test
  void refinoQueEsvaziariaTudoMantemAVarreduraSemRefino() throws IOException {
    escrever("pom.xml", "<project><modules><module>nope</module></modules></project>");
    escrever("lib/src/main/java/L.java", "class L {}");

    assertThat(raizes(detectarAqui()))
        .containsExactly(new Raiz("lib/src/main/java", SourceRootKind.MAIN, 1));
  }

  @Test
  void pastaDeclaradaNoPomViraRaizEOArquivoVaiParaARaizMaisFunda() throws IOException {
    escrever(
        "pom.xml",
        "<project><build><sourceDirectory>src</sourceDirectory>"
            + "<testSourceDirectory>sem-java</testSourceDirectory></build></project>");
    escrever("src/com/x/A.java", "package com.x; class A {}");
    escrever("src/main/java/com/y/B.java", "package com.y; class B {}");
    escrever("sem-java/leia-me.txt", "nada");

    assertThat(raizes(detectarAqui()))
        .as("B está sob src e sob src/main/java; vai para a mais funda, e src fica só com A")
        .containsExactly(
            new Raiz("src", SourceRootKind.MAIN, 1),
            new Raiz("src/main/java", SourceRootKind.MAIN, 1));
  }

  @Test
  void conjuntosDeFonteDoGradleNosModulosIncluidos() throws IOException {
    escrever("settings.gradle", "include 'app'");
    escrever("app/src/main/java/a/Main.java", "package a; class Main {}");
    escrever("app/src/integrationTest/java/a/MainIT.java", "package a; class MainIT {}");
    escrever("src/testFixtures/java/f/Fixture.java", "package f; class Fixture {}");
    escrever("src/jmh/java/b/Bench.java", "package b; class Bench {}");
    escrever("tools/src/custom/java/t/Tool.java", "package t; class Tool {}");
    escrever(
        "app/build/generated/sources/annotationProcessor/java/main/a/Gen.java",
        "package a; class Gen {}");

    final ProjectLayout layout = detectarAqui();

    assertThat(layout.buildSystem()).isEqualTo(BuildSystem.GRADLE);
    assertThat(raizes(layout))
        .as("tools não é módulo incluído, então src/custom/java dele não é conjunto de fonte")
        .containsExactly(
            new Raiz(
                "app/build/generated/sources/annotationProcessor/java/main",
                SourceRootKind.GENERATED,
                1),
            new Raiz("app/src/integrationTest/java", SourceRootKind.TEST, 1),
            new Raiz("app/src/main/java", SourceRootKind.MAIN, 1),
            new Raiz("src/jmh/java", SourceRootKind.MAIN, 1),
            new Raiz("src/testFixtures/java", SourceRootKind.TEST, 1));
  }

  @Test
  void layoutPlanoDoAntEInferidoPeloPackageComMainTestEGenerated() throws IOException {
    escrever("build.xml", "<project name=\"legado\"/>");
    escrever("src/com/x/A.java", "/* Licença. */\npackage com.x;\nclass A {}");
    escrever("src/com/x/B.java", "package com.x; class B {}");
    escrever("src/Main.java", "public class Main {}");
    escrever("test/com/x/ATest.java", "package com.x; class ATest {}");
    escrever("generated/com/x/Gen.java", "package com.x; class Gen {}");
    escrever("misc/Tool.java", "package tools; class Tool {}");

    final ProjectLayout layout = detectarAqui();

    assertThat(layout.buildSystem()).isEqualTo(BuildSystem.ANT);
    assertThat(raizes(layout))
        .as("o pacote padrão e o pacote que não bate com a pasta ficam na própria pasta")
        .containsExactly(
            new Raiz("generated", SourceRootKind.GENERATED, 1),
            new Raiz("misc", SourceRootKind.MAIN, 1),
            new Raiz("src", SourceRootKind.MAIN, 3),
            new Raiz("test", SourceRootKind.TEST, 1));
  }

  @Test
  void raizDoRepositorioTemRotuloVazio() throws IOException {
    escrever("Main.java", "class Main {}");
    escrever("com/x/A.java", "package com.x; class A {}");

    assertThat(raizes(detectarAqui())).containsExactly(new Raiz("", SourceRootKind.MAIN, 2));
  }

  @Test
  void fonteAcimaDoTetoNaoELidoEFicaNaPropriaPasta() throws IOException {
    final String cabeca = "package com.y;\nclass Big {}\n//";
    escrever(
        "big/com/y/Big.java",
        cabeca + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES + 1 - cabeca.length()));

    assertThat(raizes(detectarAqui()))
        .containsExactly(new Raiz("big/com/y", SourceRootKind.MAIN, 1));
  }

  @Test
  void prefixoEComparadoPorComponenteEOPadraoMaisAEsquerdaVence() throws IOException {
    escrever("core/src/main/java/a/X.java", "package a; class X {}");
    escrever("core/src/main/java2/a/Y.java", "package a; class Y {}");
    escrever("src/main/java/a/src/test/java/Z.java", "class Z {}");

    assertThat(raizes(detectarAqui()))
        .as("java2 não está sob java; Z pertence a src/main/java, não a .../src/test/java")
        .containsExactly(
            new Raiz("core/src/main/java", SourceRootKind.MAIN, 1),
            new Raiz("src/main/java", SourceRootKind.MAIN, 1));
  }

  @Test
  void nomeComU202eEContadoENuncaSaneado() throws IOException {
    final Path corpus = TestRepositories.corpus("unusual-names");
    final RepositoryInventory inventario = TestRepositories.inventoryOf(corpus);
    escrever("rel\u202Eatório/src/main/java/A.java", "class A {}");

    assertThat(inventario.javaSources())
        .extracting(JavaSource::relativePath)
        .contains("src/main/java/br/pucpr/pedidos/Relatório\u202Egpj.java");
    assertThat(new ProjectLayoutDetector().detect(corpus, inventario).sourceRoots())
        .extracting(SourceRoot::javaFileCount)
        .containsExactly(3);
    assertThat(detectarAqui().sourceRoots())
        .as("o caminho da raiz sai cru, U+202E incluído; neutralizar é trabalho do frontend")
        .containsExactly(
            new SourceRoot(
                SourceRootId.derive("rel\u202Eatório/src/main/java"),
                "rel\u202Eatório/src/main/java",
                SourceRootKind.MAIN,
                1));
  }

  @Test
  void duasRaizesComOMesmoIdSaoRecusadasEmVozAlta() throws IOException {
    final String primeira = "m73216/src/main/java";
    final String segunda = "m1112763/src/main/java";
    escrever(primeira + "/A.java", "class A {}");
    escrever(segunda + "/B.java", "class B {}");

    assertThat(SourceRootId.derive(primeira))
        .as("pré-condição: o par colide de verdade")
        .isEqualTo(SourceRootId.derive(segunda))
        .isEqualTo(new SourceRootId("sr_egq2td0h"));
    assertThatThrownBy(this::detectarAqui)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sr_egq2td0h");
  }
}
