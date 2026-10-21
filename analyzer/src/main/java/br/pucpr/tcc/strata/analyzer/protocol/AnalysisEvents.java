package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.Completeness;
import br.pucpr.tcc.strata.analyzer.analysis.ProgressTick;
import br.pucpr.tcc.strata.analyzer.analysis.ProgressUnit;
import br.pucpr.tcc.strata.analyzer.analysis.StageCounters;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.Map;
import java.util.Objects;

public final class AnalysisEvents {

  private AnalysisEvents() {}

  public static Envelope.Event started(AnalysisId id) {
    return event("analysis.started", new AnalysisPayload(id.value()));
  }

  public static Envelope.Event progress(AnalysisId id, ProgressTick tick) {
    return event(
        "analysis.progress",
        new ProgressPayload(id.value(), tick.completed(), tick.stage(), tick.total(), tick.unit()));
  }

  public static Envelope.Event stageCompleted(
      AnalysisId id,
      AnalysisStage stage,
      StageStatus status,
      long durationMs,
      StageCounters counters) {
    Objects.requireNonNull(stage, "stage");
    if (status == StageStatus.PENDING || status == StageStatus.RUNNING) {
      throw new IllegalArgumentException("um estágio concluído tem status terminal: " + status);
    }
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs deve ser >= 0: " + durationMs);
    }
    return event(
        "analysis.stage.completed",
        new StageCompletedPayload(
            id.value(), counters.values(), durationMs, stage, Objects.requireNonNull(status)));
  }

  public static Envelope.Event warning(AnalysisId id, ProtocolError warning) {
    return event(
        "analysis.warning",
        new ErrorPayload(id.value(), Objects.requireNonNull(warning, "warning")));
  }

  public static Envelope.Event completed(AnalysisId id, Completeness completeness) {
    return event(
        "analysis.completed",
        new CompletenessPayload(id.value(), Objects.requireNonNull(completeness, "completeness")));
  }

  public static Envelope.Event cancelled(AnalysisId id, Completeness completeness) {
    return event(
        "analysis.cancelled",
        new CompletenessPayload(id.value(), Objects.requireNonNull(completeness, "completeness")));
  }

  public static Envelope.Event failed(AnalysisId id, ProtocolError error) {
    return event(
        "analysis.failed", new ErrorPayload(id.value(), Objects.requireNonNull(error, "error")));
  }

  private static Envelope.Event event(String name, Object payload) {
    return new Envelope.Event(name, payload, AnalysisConstants.PROTOCOL_VERSION);
  }

  public record AnalysisPayload(String analysisId) {}

  public record ProgressPayload(
      String analysisId, long completed, AnalysisStage stage, Long total, ProgressUnit unit) {}

  public record StageCompletedPayload(
      String analysisId,
      Map<String, Long> counters,
      long durationMs,
      AnalysisStage stage,
      StageStatus status) {}

  public record ErrorPayload(String analysisId, ProtocolError error) {}

  public record CompletenessPayload(String analysisId, Completeness completeness) {}
}
