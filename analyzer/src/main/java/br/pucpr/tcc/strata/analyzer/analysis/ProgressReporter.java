package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;
import java.util.function.Consumer;

public final class ProgressReporter {

  private final AnalysisId id;
  private final AnalysisStage stage;
  private final ProgressThrottle throttle;
  private final Consumer<ProgressTick> sink;

  public ProgressReporter(
      AnalysisId id, AnalysisStage stage, ProgressThrottle throttle, Consumer<ProgressTick> sink) {
    this.id = Objects.requireNonNull(id, "id");
    this.stage = Objects.requireNonNull(stage, "stage");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  public void report(ProgressUnit unit, long completed, Long total) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public void finish(ProgressUnit unit, long completed, Long total) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
