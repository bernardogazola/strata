package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import java.util.Objects;

public record StageRecord(
    AnalysisStage stage, StageStatus status, Long startedAt, Long durationMs, String countersJson) {

  public StageRecord {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(countersJson, "countersJson");
  }
}
