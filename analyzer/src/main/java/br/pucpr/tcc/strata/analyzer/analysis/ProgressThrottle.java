package br.pucpr.tcc.strata.analyzer.analysis;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class ProgressThrottle {

  private final LongSupplier nanoClock;
  private final long intervalNanos;

  private final ConcurrentMap<Key, AtomicLong> lastAdmittedNanos = new ConcurrentHashMap<>();

  public ProgressThrottle(LongSupplier nanoClock, Duration interval) {
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    Objects.requireNonNull(interval, "interval");
    if (interval.isNegative()) {
      throw new IllegalArgumentException(
          "o intervalo do throttle não pode ser negativo: " + interval);
    }
    this.intervalNanos = interval.toNanos();
  }

  public boolean admit(AnalysisId id, AnalysisStage stage, boolean terminal) {
    final long now = nanoClock.getAsLong();
    final AtomicLong last = lastAdmittedNanos.putIfAbsent(new Key(id, stage), new AtomicLong(now));
    if (last == null) {
      return true;
    }
    if (terminal) {
      last.accumulateAndGet(now, ProgressThrottle::later);
      return true;
    }
    final long previous = last.get();
    return now - previous >= intervalNanos && last.compareAndSet(previous, now);
  }

  public void forget(AnalysisId id) {
    Objects.requireNonNull(id, "id");
    for (final AnalysisStage stage : AnalysisStage.values()) {
      lastAdmittedNanos.remove(new Key(id, stage));
    }
  }

  private static long later(long stamped, long candidate) {
    return candidate - stamped > 0 ? candidate : stamped;
  }

  private record Key(AnalysisId id, AnalysisStage stage) {

    private Key {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(stage, "stage");
    }
  }
}
