package br.pucpr.tcc.strata.analyzer.analysis;

public enum AnalysisStatus {
  RUNNING,
  COMPLETED,
  CANCELLED,
  FAILED;

  public boolean isTerminal() {
    return this != RUNNING;
  }
}
