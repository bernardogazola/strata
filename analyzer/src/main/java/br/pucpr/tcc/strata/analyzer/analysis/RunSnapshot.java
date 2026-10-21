package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public record RunSnapshot(
    AnalysisId analysisId,
    AnalysisStatus status,
    long startedAtMillis,
    Long finishedAtMillis,
    String headCommitId,
    Map<AnalysisStage, StageSnapshot> stages,
    Completeness completeness,
    ProtocolError failure) {

  public RunSnapshot {
    Objects.requireNonNull(analysisId, "analysisId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stages, "stages");
    Objects.requireNonNull(completeness, "completeness");
    if (!stages.keySet().equals(EnumSet.allOf(AnalysisStage.class))) {
      throw new IllegalArgumentException(
          "um snapshot tem todos os dez estágios: " + stages.keySet());
    }
    final EnumMap<AnalysisStage, StageSnapshot> copy = new EnumMap<>(AnalysisStage.class);
    for (final Map.Entry<AnalysisStage, StageSnapshot> entry : stages.entrySet()) {
      copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "stage snapshot"));
    }
    stages = Collections.unmodifiableMap(copy);
    if (status.isTerminal() != (finishedAtMillis != null)) {
      throw new IllegalArgumentException(
          "finishedAtMillis existe se e somente se a análise terminou: "
              + status
              + " "
              + finishedAtMillis);
    }
    if (finishedAtMillis != null && finishedAtMillis < startedAtMillis) {
      throw new IllegalArgumentException("uma análise não pode terminar antes de começar");
    }
    if ((status == AnalysisStatus.FAILED) != (failure != null)) {
      throw new IllegalArgumentException(
          "failure existe se e somente se a análise está FAILED: " + status);
    }
  }

  public static RunSnapshot started(AnalysisId analysisId, long startedAtMillis) {
    final EnumMap<AnalysisStage, StageSnapshot> stages = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : AnalysisStage.values()) {
      stages.put(stage, StageSnapshot.pending());
    }
    return new RunSnapshot(
        analysisId,
        AnalysisStatus.RUNNING,
        startedAtMillis,
        null,
        null,
        stages,
        Completeness.none(),
        null);
  }

  public RunSnapshot withStage(AnalysisStage stage, StageSnapshot snapshot) {
    final EnumMap<AnalysisStage, StageSnapshot> copy = new EnumMap<>(stages);
    copy.put(Objects.requireNonNull(stage, "stage"), Objects.requireNonNull(snapshot, "snapshot"));
    return new RunSnapshot(
        analysisId,
        status,
        startedAtMillis,
        finishedAtMillis,
        headCommitId,
        copy,
        completeness,
        failure);
  }

  public RunSnapshot withHeadCommitId(String commitId) {
    return new RunSnapshot(
        analysisId,
        status,
        startedAtMillis,
        finishedAtMillis,
        commitId,
        stages,
        completeness,
        failure);
  }

  public RunSnapshot withCompleteness(Completeness counted) {
    return new RunSnapshot(
        analysisId,
        status,
        startedAtMillis,
        finishedAtMillis,
        headCommitId,
        stages,
        counted,
        failure);
  }

  public RunSnapshot finished(AnalysisStatus outcome, long finishedAt, ProtocolError error) {
    return new RunSnapshot(
        analysisId,
        outcome,
        startedAtMillis,
        finishedAt,
        headCommitId,
        stages,
        completeness,
        error);
  }

  public record StageSnapshot(
      StageStatus status, Long startedAtMillis, Long durationMs, StageCounters counters) {

    private static final StageSnapshot PENDING =
        new StageSnapshot(StageStatus.PENDING, null, null, StageCounters.none());

    public StageSnapshot {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(counters, "counters");
      final boolean started = status != StageStatus.PENDING;
      final boolean finished = started && status != StageStatus.RUNNING;
      if (started != (startedAtMillis != null) || finished != (durationMs != null)) {
        throw new IllegalArgumentException(
            "startedAt/durationMs não combinam com "
                + status
                + ": "
                + startedAtMillis
                + "/"
                + durationMs);
      }
      if (durationMs != null && durationMs < 0) {
        throw new IllegalArgumentException("durationMs deve ser >= 0: " + durationMs);
      }
      if (!finished && !counters.values().isEmpty()) {
        throw new IllegalArgumentException("só um estágio terminado tem contadores: " + status);
      }
    }

    public static StageSnapshot pending() {
      return PENDING;
    }

    public static StageSnapshot running(long startedAtMillis) {
      return new StageSnapshot(StageStatus.RUNNING, startedAtMillis, null, StageCounters.none());
    }

    public static StageSnapshot finished(
        StageStatus outcome, long startedAtMillis, long durationMs, StageCounters counters) {
      return new StageSnapshot(outcome, startedAtMillis, durationMs, counters);
    }
  }
}
