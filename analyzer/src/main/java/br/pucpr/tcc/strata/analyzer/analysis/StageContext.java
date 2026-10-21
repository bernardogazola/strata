package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import java.util.Objects;

public record StageContext(
    AnalysisId analysisId,
    AnalysisStage stage,
    RunInputs inputs,
    SqliteConnections connections,
    long analysisPk,
    CancellationToken cancellation,
    ProgressReporter progress,
    WarningSink warnings,
    CompletenessRecorder completeness) {

  public StageContext {
    Objects.requireNonNull(analysisId, "analysisId");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(connections, "connections");
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(progress, "progress");
    Objects.requireNonNull(warnings, "warnings");
    Objects.requireNonNull(completeness, "completeness");
    if (!StagePlan.SPI_STAGES.contains(stage)) {
      throw new IllegalArgumentException("não é um estágio da SPI: " + stage);
    }
    if (analysisPk < 1) {
      throw new IllegalArgumentException("analysisPk deve ser >= 1: " + analysisPk);
    }
  }
}
