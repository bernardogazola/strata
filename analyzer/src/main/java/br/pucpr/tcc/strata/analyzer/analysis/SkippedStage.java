package br.pucpr.tcc.strata.analyzer.analysis;

public enum SkippedStage implements StageExecutor {
  INSTANCE;

  @Override
  public StageOutcome execute(StageContext context) {
    return StageOutcome.skipped();
  }
}
