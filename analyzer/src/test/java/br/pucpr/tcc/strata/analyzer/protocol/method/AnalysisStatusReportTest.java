package br.pucpr.tcc.strata.analyzer.protocol.method;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.Completeness;
import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot;
import br.pucpr.tcc.strata.analyzer.analysis.StageCounters;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AnalysisStatusReportTest {

  private static final Path FIXTURES =
      Path.of(
          Optional.ofNullable(System.getProperty("strata.protocol.fixtures"))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "-Dstrata.protocol.fixtures não foi definido; veja o surefire no pom")));

  private static final AnalysisId ID = new AnalysisId("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  private static final long INSTANTE = Instant.parse("2024-01-02T03:04:05Z").toEpochMilli();

  private static final ObjectMapper JSON = new ObjectMapper();

  private final CanonicalJson canonical = new CanonicalJson();

  private String noFio(RunSnapshot snapshot) {
    return canonical.valueToString(AnalysisStatusReport.of(snapshot));
  }

  private static JsonNode payloadDaFixture() throws IOException {
    final List<String> linhas =
        Files.readString(
                FIXTURES.resolve("analysis.status.completed.jsonl"), StandardCharsets.UTF_8)
            .lines()
            .toList();
    assertThat(linhas).hasSize(2);
    return JSON.readTree(linhas.get(1)).path("payload");
  }

  @Test
  void umRetratoComOsValoresDaFixtureSaiByteAByteComoOPayloadDela() throws IOException {
    final JsonNode esperado = payloadDaFixture();
    RunSnapshot retrato =
        RunSnapshot.started(ID, Instant.parse(esperado.path("startedAt").asText()).toEpochMilli())
            .withHeadCommitId(esperado.path("headCommitId").asText())
            .withCompleteness(JSON.treeToValue(esperado.path("completeness"), Completeness.class));
    for (final JsonNode estagio : esperado.path("stages")) {
      final Map<String, Long> contadores = new LinkedHashMap<>();
      estagio
          .path("counters")
          .properties()
          .forEach(e -> contadores.put(e.getKey(), e.getValue().asLong()));
      retrato =
          retrato.withStage(
              AnalysisStage.valueOf(estagio.path("stage").asText()),
              RunSnapshot.StageSnapshot.finished(
                  StageStatus.valueOf(estagio.path("status").asText()),
                  Instant.parse(estagio.path("startedAt").asText()).toEpochMilli(),
                  estagio.path("durationMs").asLong(),
                  StageCounters.of(contadores)));
    }
    retrato =
        retrato.finished(
            AnalysisStatus.COMPLETED,
            Instant.parse(esperado.path("finishedAt").asText()).toEpochMilli(),
            null);

    assertThat(noFio(retrato)).isEqualTo(canonical.valueToString(esperado));
  }

  @Test
  void oInstanteETruncadoAosSegundosSemFracao() {
    assertThat(AnalysisStatusReport.timestamp(INSTANTE + 999)).isEqualTo("2024-01-02T03:04:05Z");
    assertThat(AnalysisStatusReport.timestamp(INSTANTE)).isEqualTo("2024-01-02T03:04:05Z");
    assertThat(AnalysisStatusReport.timestamp(null)).isNull();
  }

  @Test
  void umaAnaliseRodandoEscreveTodaChaveComNullEOsDezEstagiosEmOrdem() throws IOException {
    final RunSnapshot retrato =
        RunSnapshot.started(ID, INSTANTE + 500)
            .withStage(
                AnalysisStage.REPOSITORY_VALIDATION, RunSnapshot.StageSnapshot.running(INSTANTE));

    final JsonNode relatorio = JSON.readTree(noFio(retrato));

    assertThat(relatorio.path("status").asText()).isEqualTo("RUNNING");
    assertThat(relatorio.path("startedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
    for (final String nula : List.of("error", "finishedAt", "headCommitId")) {
      assertThat(relatorio.has(nula)).as(nula + " sai sempre").isTrue();
      assertThat(relatorio.path(nula).isNull()).as(nula).isTrue();
    }
    final List<String> ordem = new ArrayList<>();
    relatorio.path("stages").forEach(e -> ordem.add(e.path("stage").asText()));
    assertThat(ordem)
        .containsExactly(
            Arrays.stream(AnalysisStage.values()).map(Enum::name).toArray(String[]::new));
    final JsonNode rodando = relatorio.path("stages").get(0);
    assertThat(rodando.path("status").asText()).isEqualTo("RUNNING");
    assertThat(rodando.path("startedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
    assertThat(rodando.has("durationMs") && rodando.path("durationMs").isNull()).isTrue();
    final JsonNode pendente = relatorio.path("stages").get(AnalysisStage.PERSISTENCE.ordinal());
    assertThat(pendente.has("startedAt") && pendente.path("startedAt").isNull()).isTrue();
    assertThat(pendente.has("durationMs") && pendente.path("durationMs").isNull()).isTrue();
    assertThat(pendente.path("counters").isObject() && pendente.path("counters").isEmpty())
        .isTrue();
  }

  @Test
  void umaAnaliseQueFalhouCarregaOErro() throws IOException {
    final RunSnapshot retrato =
        RunSnapshot.started(ID, INSTANTE)
            .finished(
                AnalysisStatus.FAILED,
                INSTANTE + 1_000,
                ErrorCodes.repository(RepositoryIssue.noJavaSources()));

    final JsonNode relatorio = JSON.readTree(noFio(retrato));

    assertThat(relatorio.path("status").asText()).isEqualTo("FAILED");
    assertThat(relatorio.path("finishedAt").asText()).isEqualTo("2024-01-02T03:04:06Z");
    assertThat(relatorio.path("error"))
        .isEqualTo(JSON.valueToTree(ErrorCodes.repository(RepositoryIssue.noJavaSources())));
  }
}
