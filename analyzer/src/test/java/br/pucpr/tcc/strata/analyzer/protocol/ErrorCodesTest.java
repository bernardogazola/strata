package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ErrorCodesTest {

  private static final Path FIXTURES =
      Path.of(
          Optional.ofNullable(System.getProperty("strata.protocol.fixtures"))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "-Dstrata.protocol.fixtures não foi definido; veja o surefire no pom")));

  private static void assertFixedAnalyzerError(ProtocolError error, String code) {
    assertThat(error.code()).isEqualTo(code);
    assertThat(error.origin()).isEqualTo(ErrorCodes.ORIGIN_ANALYZER);
    assertThat(error.detail()).isNotBlank();
  }

  @Test
  void analysisAlreadyRunningFalaDoProcessoENaoDoRepositorio() {
    final ProtocolError error = ErrorCodes.analysisAlreadyRunning();

    assertFixedAnalyzerError(error, "ANALYSIS_ALREADY_RUNNING");
    assertThat(error.detail()).isEqualTo("já há uma análise rodando neste processo do analisador");
    assertThat(error.params()).isEmpty();
  }

  @Test
  void analysisNotFoundTemOsBytesDaFixture() throws IOException {
    final List<String> linhas =
        Files.readAllLines(
            FIXTURES.resolve("analysis.status.not-found.jsonl"), StandardCharsets.UTF_8);
    final JsonNode resposta = JsonLines.strictInboundMapper().readTree(linhas.get(1));
    final String id = resposta.get("id").asText();

    final ProtocolError error = ErrorCodes.analysisNotFound();

    assertFixedAnalyzerError(error, "ANALYSIS_NOT_FOUND");
    assertThat(error.params()).isEmpty();
    assertThat(
            new String(
                new CanonicalJson().toBytes(Envelope.error(id, error)), StandardCharsets.UTF_8))
        .isEqualTo(linhas.get(1));
  }

  @Test
  void tooManyJavaSourcesLevaOTetoEOEncontrado() {
    final ProtocolError error = ErrorCodes.tooManyJavaSources(60_000, 60_001);

    assertFixedAnalyzerError(error, "ANALYSIS_TOO_MANY_JAVA_SOURCES");
    assertThat(error.detail())
        .isEqualTo(
            "as raízes de código selecionadas têm mais arquivos Java que o teto de arquivos analisados")
        .doesNotContain("60000")
        .doesNotContain("60001");
    assertThat(error.params()).isEqualTo(Map.of("limit", 60_000, "found", 60_001));
  }

  @Test
  void cacheUnavailableMapeiaTodoKindComFraseDistinta() {
    final Set<String> frases = new HashSet<>();
    for (final CacheUnavailableException.Kind kind : CacheUnavailableException.Kind.values()) {
      final ProtocolError error = ErrorCodes.cacheUnavailable(kind);

      assertFixedAnalyzerError(error, "CACHE_UNAVAILABLE");
      assertThat(error.params()).as(kind.name()).isEqualTo(Map.of("cause", kind.name()));
      assertThat(frases.add(error.detail())).as(kind + ": frase repetida").isTrue();
    }
    assertThat(frases).hasSize(CacheUnavailableException.Kind.values().length).hasSize(4);
  }

  @Test
  void sourceMapeiaTodoCodigoComFraseDistintaEParametrosIntactos() {
    final Set<String> frases = new HashSet<>();
    for (final SourceIssue.Code code : SourceIssue.Code.values()) {
      final SourceIssue issue =
          new SourceIssue(
              code, Map.of("filePath", "src/main/java/a/Segredo.java", "limitBytes", 2_097_152));

      final ProtocolError error = ErrorCodes.source(issue);

      assertThat(error.code()).isEqualTo(code.name());
      assertThat(error.origin()).isEqualTo(ErrorCodes.ORIGIN_ANALYZER);
      assertThat(error.params()).isEqualTo(issue.params());
      assertThat(error.detail()).as(code.name()).isNotBlank().doesNotContain("Segredo");
      assertThat(frases.add(error.detail())).as(code + ": frase repetida").isTrue();
    }
    assertThat(frases).hasSize(4);
  }

  @Test
  void sourceParseFailedTemAFraseDaFixture() {
    assertThat(ErrorCodes.source(SourceIssue.parseFailed("a/B.java")).detail())
        .isEqualTo("o arquivo não pôde ser modelado e ficou de fora da análise estrutural");
  }
}
