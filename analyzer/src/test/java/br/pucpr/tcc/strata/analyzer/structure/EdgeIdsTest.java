package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class EdgeIdsTest {

  private static final Path FIXTURES =
      Path.of(
          Optional.ofNullable(System.getProperty("strata.protocol.fixtures"))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "-Dstrata.protocol.fixtures não foi definido; veja o surefire no pom")));

  private static final List<String> MULTI_MODULE_FIXTURES =
      List.of(
          "analysis.status.completed.jsonl",
          "graph.search.hit.jsonl",
          "graph.slice.package.jsonl",
          "graph.slice.type.jsonl",
          "graph.slice.type-api.jsonl",
          "graph.slice.type-core-gen.jsonl",
          "graph.slice.type-core-spi.jsonl",
          "outline.children.root.jsonl",
          "outline.children.api.jsonl",
          "outline.children.core.jsonl",
          "outline.children.core-gen.jsonl",
          "outline.children.core-spi.jsonl",
          "outline.children.unknown-parent.jsonl");

  private static final Pattern EDGE_ID = Pattern.compile("^e_[0-9a-f]{16}$");

  private static final ObjectMapper JSON = new ObjectMapper();

  private record FixtureEdge(String fixture, String id, String source, String target) {}

  private static List<FixtureEdge> fixtureEdges() throws IOException {
    final List<FixtureEdge> edges = new ArrayList<>();
    for (final String fixture : MULTI_MODULE_FIXTURES) {
      for (final String line :
          Files.readAllLines(FIXTURES.resolve(fixture), StandardCharsets.UTF_8)) {
        for (final JsonNode edge : JSON.readTree(line).path("payload").path("edges")) {
          edges.add(
              new FixtureEdge(
                  fixture,
                  edge.path("id").asText(),
                  edge.path("source").asText(),
                  edge.path("target").asText()));
        }
      }
    }
    return List.copyOf(edges);
  }

  @Test
  void oVetorDeReferenciaDoFnv1a64DeAEAf63dc4c8601ec8c() {
    assertThat(EdgeIds.fnv1a64("a".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(0xaf63dc4c8601ec8cL);
  }

  @Test
  void aEntradaVaziaEABaseEFoobarEOVetorPublicado() {
    assertThat(EdgeIds.fnv1a64(new byte[0])).isEqualTo(EdgeIds.FNV_OFFSET_BASIS);
    assertThat(EdgeIds.fnv1a64(new byte[0])).isEqualTo(0xcbf29ce484222325L);
    assertThat(EdgeIds.fnv1a64("foobar".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(0x85944171f73967e8L);
  }

  @Test
  void todaArestaDasFixturesDoMultiModuleTemOIdDoPar() throws IOException {
    final List<FixtureEdge> edges = fixtureEdges();

    assertThat(edges).hasSize(9);
    for (final FixtureEdge edge : edges) {
      assertThat(EdgeIds.of(edge.source(), edge.target()))
          .as("%s: %s -> %s", edge.fixture(), edge.source(), edge.target())
          .isEqualTo(edge.id());
    }
  }

  @Test
  void oIdEDoParOrdenado() {
    assertThat(EdgeIds.of("pkg:br.pucpr.legacy.core", "pkg:br.pucpr.legacy.core.spi"))
        .isEqualTo("e_a1ae1e791b4acaf3");
    assertThat(EdgeIds.of("pkg:br.pucpr.legacy.core.spi", "pkg:br.pucpr.legacy.core"))
        .isEqualTo("e_728271917ac6aba3");
    assertThat(EdgeIds.of("pkg:br.pucpr.legacy.core", "pkg:br.pucpr.legacy.api"))
        .isEqualTo("e_1344286866c3e418");
  }

  @Test
  void oSeparadorNulImpedeAColisaoPorConcatenacao() {
    assertThat(EdgeIds.of("a.b", "c")).isEqualTo("e_06f227b51397fbc5");
    assertThat(EdgeIds.of("a", "b.c")).isEqualTo("e_3f6446fc845b6b35");
  }

  @Test
  void osIdsNaoAsciiDoUnusualNamesSaoHashDosBytesUtf8() {
    final String nfc = Character.toString(0xE7) + Character.toString(0xE3);
    final String avaliacao = "avalia" + nfc + "o";
    final String avaliacaoTipo = "Avalia" + nfc + "o";
    assertThat((avaliacao + avaliacaoTipo).getBytes(StandardCharsets.UTF_8)).hasSize(22);

    assertThat(EdgeIds.of("pkg:br.pucpr.pedidos", "pkg:br.pucpr.pedidos." + avaliacao))
        .isEqualTo("e_0a5d3ac02713b4a4");
    assertThat(
            EdgeIds.of(
                "type:br.pucpr.pedidos.Pedido",
                "type:br.pucpr.pedidos." + avaliacao + "." + avaliacaoTipo))
        .isEqualTo("e_7555c0208a1ebc02");
  }

  @Test
  void umCaractereDeQuatroBytesEntraComoUtf8EnaoComoParSubstituto() {
    final String negrito = Character.toString(0x1D400);
    final String sorriso = Character.toString(0x1F600);

    assertThat(EdgeIds.of("type:p." + negrito, "type:p." + sorriso))
        .isEqualTo("e_ea2b3fd165c136f5");
  }

  @Test
  void oIdSempreTem16HexadecimaisMinusculosComZerosAEsquerda() {
    final String zeros = EdgeIds.of("pfx:a", "pkg:b25000");

    assertThat(zeros).isEqualTo("e_0045807b1fb40049");
    assertThat(zeros).matches(EDGE_ID);
    assertThat(EdgeIds.of("pkg:br.pucpr.legacy.api", "pkg:br.pucpr.legacy.core"))
        .isEqualTo("e_8a135394c938e16e")
        .matches(EDGE_ID);
  }
}
