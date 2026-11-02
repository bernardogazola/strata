package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.protocol.AnalysisEvents;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class RunEventsSerializationRefusalTest {

  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private final CancellationToken token = CancellationToken.create();
  private PrintStream originalErr;
  private RunEvents events;

  @BeforeEach
  void montar() {
    originalErr = System.err;
    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    events =
        new RunEvents(
            RunFixtures.FIXTURE_ID,
            new JsonLines.Writer(stdout, new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES),
            token);
  }

  @AfterEach
  void restaurar() {
    System.setErr(originalErr);
  }

  private static ProtocolError avisoDeVerdade() {
    return ErrorCodes.source(SourceIssue.parseFailed("src/main/java/a/A.java"));
  }

  private static ProtocolError avisoComNaN() {
    final ProtocolError real = avisoDeVerdade();
    return new ProtocolError(
        real.code(), real.detail(), real.origin(), Map.of("ratio", Double.NaN));
  }

  private static String linha(Envelope envelope) {
    return new String(new CanonicalJson().toBytes(envelope), StandardCharsets.UTF_8) + "\n";
  }

  @Test
  void umEventoQueNaoSerializaELogadoEDescartadoEOSeguinteSai() {
    assertThatCode(() -> events.warn(avisoComNaN()))
        .as("a recusa do evento não escapa da fachada, que nunca lança")
        .doesNotThrowAnyException();
    events.warn(avisoDeVerdade());
    events.stageCompleted(
        AnalysisStage.STRUCTURAL_GRAPH, StageStatus.COMPLETED, 0L, StageCounters.none());

    assertThat(stdout.toString(StandardCharsets.UTF_8))
        .as("o stdout inteiro: nenhum byte do evento recusado, e os dois seguintes")
        .isEqualTo(
            linha(AnalysisEvents.warning(RunFixtures.FIXTURE_ID, avisoDeVerdade()))
                + linha(
                    AnalysisEvents.stageCompleted(
                        RunFixtures.FIXTURE_ID,
                        AnalysisStage.STRUCTURAL_GRAPH,
                        StageStatus.COMPLETED,
                        0L,
                        StageCounters.none())));
    assertThat(token.isCancelled())
        .as("uma recusa de serialização não é o stdout quebrado: a análise segue")
        .isFalse();
    assertThat(stderr.toString(StandardCharsets.UTF_8))
        .as("o descarte é logado, com a análise")
        .contains(RunFixtures.FIXTURE_ID.value());
  }

  @Test
  void depoisDeUmaRecusaOTerminalAindaSai() {
    events.warn(avisoComNaN());
    events.cancelled(new Completeness(9, 0, 0, false, null));

    assertThat(stdout.toString(StandardCharsets.UTF_8))
        .isEqualTo(
            linha(
                AnalysisEvents.cancelled(
                    RunFixtures.FIXTURE_ID, new Completeness(9, 0, 0, false, null))));
    assertThat(events.terminalEmitted()).isTrue();
    assertThat(token.isCancelled()).isFalse();
  }
}
