package br.pucpr.tcc.strata.analyzer.analysis;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ProgressThrottle {

  private final LongSupplier nanoClock;
  private final Duration interval;

  public ProgressThrottle(LongSupplier nanoClock, Duration interval) {
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.interval = Objects.requireNonNull(interval, "interval");
  }

  public boolean admit(AnalysisId id, AnalysisStage stage, boolean terminal) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public void forget(AnalysisId id) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
