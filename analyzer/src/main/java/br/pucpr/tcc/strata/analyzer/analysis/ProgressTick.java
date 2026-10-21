package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;

public record ProgressTick(AnalysisStage stage, ProgressUnit unit, long completed, Long total) {

  public ProgressTick {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(unit, "unit");
    if (completed < 0) {
      throw new IllegalArgumentException("completed deve ser >= 0: " + completed);
    }
    if (total != null && (total < 0 || completed > total)) {
      throw new IllegalArgumentException(
          "um total conhecido deve ser >= 0 e >= completed: " + completed + " / " + total);
    }
  }
}
