package br.pucpr.tcc.strata.analyzer.analysis;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class AnalysisIds implements Supplier<AnalysisId> {

  private static final String PREFIX = "an_";

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final int CHARACTERS = 26;

  private static final int BITS_PER_CHARACTER = 5;

  private static final long CHARACTER_MASK = (1L << BITS_PER_CHARACTER) - 1;

  private static final int RANDOM_HIGH_BITS = 16;

  private static final long RANDOM_HIGH_MASK = (1L << RANDOM_HIGH_BITS) - 1;

  private static final long MAX_MILLIS = (1L << 48) - 1;

  private final InstantSource clock;
  private final Supplier<? extends RandomGenerator> randomness;

  private RandomGenerator random;

  private long lastMillis = Long.MIN_VALUE;
  private long randomHigh;
  private long randomLow;

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
    final long now = clock.millis();
    if (now < 0 || now > MAX_MILLIS) {
      throw new IllegalStateException("o relógio está fora da faixa de 48 bits do ULID: " + now);
    }
    if (now > lastMillis) {
      lastMillis = now;
      draw();
    } else {
      increment();
    }
    return new AnalysisId(PREFIX + encode(lastMillis, randomHigh, randomLow));
  }

  private void draw() {
    if (random == null) {
      random =
          Objects.requireNonNull(randomness.get(), "o fornecedor de aleatoriedade devolveu null");
    }
    randomHigh = random.nextInt() & RANDOM_HIGH_MASK;
    randomLow = random.nextLong();
  }

  private void increment() {
    randomLow++;
    if (randomLow != 0) {
      return;
    }
    randomHigh++;
    if (randomHigh <= RANDOM_HIGH_MASK) {
      return;
    }
    if (lastMillis == MAX_MILLIS) {
      throw new IllegalStateException("o relógio de 48 bits do ULID se esgotou");
    }
    lastMillis++;
    draw();
  }

  private static String encode(long millis, long high16, long low64) {
    long high = (millis << RANDOM_HIGH_BITS) | high16;
    long low = low64;
    final char[] characters = new char[CHARACTERS];
    for (int position = CHARACTERS - 1; position >= 0; position--) {
      characters[position] = CROCKFORD.charAt((int) (low & CHARACTER_MASK));
      low = (low >>> BITS_PER_CHARACTER) | (high << (Long.SIZE - BITS_PER_CHARACTER));
      high >>>= BITS_PER_CHARACTER;
    }
    return new String(characters);
  }

  private static Supplier<RandomGenerator> constant(RandomGenerator random) {
    return () -> random;
  }
}
