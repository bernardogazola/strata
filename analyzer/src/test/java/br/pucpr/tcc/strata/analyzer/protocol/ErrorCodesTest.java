package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  void analysisCacheCorruptedTemCodigoProprioSemParametrosEFraseFixa() {
    final ProtocolError error = ErrorCodes.analysisCacheCorrupted();

    assertFixedAnalyzerError(error, "ANALYSIS_CACHE_CORRUPTED");
    assertThat(error.code()).isEqualTo(ErrorCodes.ANALYSIS_CACHE_CORRUPTED);
    assertThat(error.params()).isEmpty();
    assertThat(error.detail())
        .isEqualTo(
            "o cache da análise foi danificado durante a análise e foi reconstruído; as linhas"
                + " desta análise se perderam com ele")
        .isNotEqualTo(ErrorCodes.cacheCorrupt().detail());
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

  private static final String CAUSE_SCHEMA = "cacheUnavailableCauseSchema";

  private static final Pattern MEMBRO = Pattern.compile("\"([A-Z_]+)\"");

  private static Path enumsTs() {
    return FIXTURES.getParent().getParent().resolve("src/protocol/enums.ts");
  }

  private static Matcher blocoDoEnum(String fonte, String schema) {
    return Pattern.compile(
            Pattern.quote(schema) + "\\s*=\\s*z\\.enum\\(\\[(.*?)\\]\\)", Pattern.DOTALL)
        .matcher(fonte);
  }

  static List<String> membrosDoEnum(String fonte, String schema) {
    final Matcher bloco = blocoDoEnum(fonte, schema);
    if (!bloco.find()) {
      return List.of();
    }
    final List<String> membros = new ArrayList<>();
    final Matcher membro = MEMBRO.matcher(bloco.group(1));
    while (membro.find()) {
      membros.add(membro.group(1));
    }
    return membros;
  }

  static List<String> divergencias(List<String> java, List<String> typeScript) {
    return java.equals(typeScript)
        ? List.of()
        : List.of("Kind " + java + " != " + CAUSE_SCHEMA + " " + typeScript);
  }

  private static List<String> kinds() {
    return Arrays.stream(CacheUnavailableException.Kind.values()).map(Enum::name).toList();
  }

  @Test
  void osKindsDoCacheSaoOsMembrosDoCacheUnavailableCauseSchemaNaMesmaOrdem() throws IOException {
    final List<String> doTypeScript =
        membrosDoEnum(Files.readString(enumsTs(), StandardCharsets.UTF_8), CAUSE_SCHEMA);

    assertThat(doTypeScript)
        .as("anti-vacuidade: o schema existe em enums.ts e tem membros")
        .isNotEmpty();
    assertThat(divergencias(kinds(), doTypeScript)).isEmpty();
  }

  @Test
  void umQuintoKindDeUmLadoSoEDivergencia() throws IOException {
    final String fonte = Files.readString(enumsTs(), StandardCharsets.UTF_8);
    final Matcher bloco = blocoDoEnum(fonte, CAUSE_SCHEMA);
    assertThat(bloco.find()).isTrue();
    final String comQuinto =
        fonte.substring(0, bloco.end(1)) + "  \"DISK_FULL\",\n" + fonte.substring(bloco.end(1));
    final List<String> javaComQuinto = new ArrayList<>(kinds());
    javaComQuinto.add("DISK_FULL");

    assertThat(membrosDoEnum(comQuinto, CAUSE_SCHEMA)).hasSize(kinds().size() + 1);
    assertThat(divergencias(kinds(), membrosDoEnum(comQuinto, CAUSE_SCHEMA)))
        .as("um membro a mais só no TypeScript")
        .isNotEmpty();
    assertThat(divergencias(javaComQuinto, membrosDoEnum(fonte, CAUSE_SCHEMA)))
        .as("um Kind a mais só no Java")
        .isNotEmpty();
  }
}
