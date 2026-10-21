package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.Completeness;
import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record AnalysisStatusReport(
    String analysisId,
    Completeness completeness,
    ProtocolError error,
    String finishedAt,
    String headCommitId,
    List<StageReport> stages,
    String startedAt,
    AnalysisStatus status) {

  public static AnalysisStatusReport of(RunSnapshot snapshot) {
    final List<StageReport> stages = new ArrayList<>(AnalysisStage.values().length);
    for (final AnalysisStage stage : AnalysisStage.values()) {
      stages.add(StageReport.of(stage, snapshot.stages().get(stage)));
    }
    return new AnalysisStatusReport(
        snapshot.analysisId().value(),
        snapshot.completeness(),
        snapshot.failure(),
        timestamp(snapshot.finishedAtMillis()),
        snapshot.headCommitId(),
        List.copyOf(stages),
        timestamp(snapshot.startedAtMillis()),
        snapshot.status());
  }

  static String timestamp(Long epochMillis) {
    if (epochMillis == null) {
      return null;
    }
    return DateTimeFormatter.ISO_INSTANT.format(
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS));
  }

  public record StageReport(
      Map<String, Long> counters,
      Long durationMs,
      AnalysisStage stage,
      String startedAt,
      StageStatus status) {

    static StageReport of(AnalysisStage stage, RunSnapshot.StageSnapshot snapshot) {
      return new StageReport(
          snapshot.counters().values(),
          snapshot.durationMs(),
          stage,
          timestamp(snapshot.startedAtMillis()),
          snapshot.status());
    }
  }
}
