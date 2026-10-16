package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class SourceRootDetectorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

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
}
