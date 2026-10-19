package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;

public final class CacheDiscardException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient CacheInvalidation invalidation;

  public CacheDiscardException(CacheInvalidation invalidation, String message) {
    super(message);
    this.invalidation = Objects.requireNonNull(invalidation, "invalidation");
    if (invalidation instanceof CacheInvalidation.Outdated) {
      throw new IllegalArgumentException(
          "um cache desatualizado não é descartado: " + invalidation);
    }
  }

  public CacheInvalidation invalidation() {
    return invalidation;
  }
}
