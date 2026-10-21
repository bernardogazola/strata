package br.pucpr.tcc.strata.analyzer.analysis;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class AnalysisIds implements Supplier<AnalysisId> {

  private final InstantSource clock;
  private final Supplier<? extends RandomGenerator> randomness;

  public AnalysisIds(InstantSource clock, RandomGenerator random) {
    this(clock, constant(Objects.requireNonNull(random, "random")));
  }

  private AnalysisIds(InstantSource clock, Supplier<? extends RandomGenerator> randomness) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.randomness = Objects.requireNonNull(randomness, "randomness");
  }

  public static AnalysisIds system() {
    final Supplier<RandomGenerator> lazy = SecureRandom::new;
    return new AnalysisIds(InstantSource.system(), lazy);
  }

  @Override
  public synchronized AnalysisId get() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  private static Supplier<RandomGenerator> constant(RandomGenerator random) {
    return () -> random;
  }
}
