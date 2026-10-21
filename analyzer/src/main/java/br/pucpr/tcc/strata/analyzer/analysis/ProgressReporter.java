package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ProgressReporter {

  private final AnalysisId id;
  private final AnalysisStage stage;
  private final ProgressThrottle throttle;
  private final Consumer<ProgressTick> sink;

  private final AtomicLong highest = new AtomicLong();

  public ProgressReporter(
      AnalysisId id, AnalysisStage stage, ProgressThrottle throttle, Consumer<ProgressTick> sink) {
    this.id = Objects.requireNonNull(id, "id");
    this.stage = Objects.requireNonNull(stage, "stage");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  public void report(ProgressUnit unit, long completed, Long total) {
    emit(unit, completed, total, false);
  }

  public void finish(ProgressUnit unit, long completed, Long total) {
    emit(unit, completed, total, true);
  }

  private void emit(ProgressUnit unit, long completed, Long total, boolean terminal) {
    Objects.requireNonNull(unit, "unit");
    if (completed < 0) {
      throw new IllegalArgumentException("completed deve ser >= 0: " + completed);
    }
    final long highestSoFar = highest.accumulateAndGet(completed, Math::max);
    if (throttle.admit(id, stage, terminal)) {
      sink.accept(new ProgressTick(stage, unit, highestSoFar, total));
    }
  }
}
