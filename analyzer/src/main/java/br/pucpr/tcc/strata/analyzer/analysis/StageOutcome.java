package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;

public record StageOutcome(StageStatus status, StageCounters counters) {

  private static final StageOutcome SKIPPED =
      new StageOutcome(StageStatus.SKIPPED, StageCounters.none());

  public StageOutcome {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(counters, "counters");
    if (status != StageStatus.COMPLETED && status != StageStatus.SKIPPED) {
      throw new IllegalArgumentException("um estágio devolvido é COMPLETED ou SKIPPED: " + status);
    }
    if (status == StageStatus.SKIPPED && !counters.values().isEmpty()) {
      throw new IllegalArgumentException("um estágio SKIPPED não conta nada: " + counters.values());
    }
  }

  public static StageOutcome completed(StageCounters counters) {
    return new StageOutcome(StageStatus.COMPLETED, counters);
  }

  public static StageOutcome skipped() {
    return SKIPPED;
  }
}
