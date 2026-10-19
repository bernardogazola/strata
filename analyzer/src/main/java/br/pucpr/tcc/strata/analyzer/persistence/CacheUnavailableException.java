package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;
import java.util.Optional;

public final class CacheUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  public enum Kind {
    LOCKED,
    LOCATION_INSIDE_REPOSITORY,
    PRAGMA_REFUSED,
    STORAGE
  }

  private final Kind kind;

  private final transient CacheInvalidation discarded;

  public CacheUnavailableException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.discarded = null;
  }

  public CacheUnavailableException(
      Kind kind, String message, Throwable cause, CacheInvalidation discarded) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.discarded = Objects.requireNonNull(discarded, "discarded");
    if (discarded instanceof CacheInvalidation.Outdated) {
      throw new IllegalArgumentException("um cache desatualizado não é descartado: " + discarded);
    }
  }

  public Kind kind() {
    return kind;
  }

  public Optional<CacheInvalidation> discarded() {
    return Optional.ofNullable(discarded);
  }
}
