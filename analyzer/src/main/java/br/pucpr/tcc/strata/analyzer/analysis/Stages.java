package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.EnumMap;

public final class Stages {

  private static final StageExecutor NO_OP_PERSISTENCE =
      context -> StageOutcome.completed(StageCounters.none());

  private Stages() {}

  public static StagePlan production() {
    final EnumMap<AnalysisStage, StageExecutor> executors = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : StagePlan.SPI_STAGES) {
      executors.put(stage, SkippedStage.INSTANCE);
    }
    executors.put(AnalysisStage.PERSISTENCE, NO_OP_PERSISTENCE);
    return new StagePlan(executors);
  }
}
