package br.pucpr.tcc.strata.analyzer.bootstrap;

@FunctionalInterface
public interface Uptime {

  long millis();

  static Uptime monotonic() {
    final long startedAt = System.nanoTime();
    return () -> (System.nanoTime() - startedAt) / 1_000_000L;
  }
}
