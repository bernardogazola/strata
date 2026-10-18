package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;

public final class CacheUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  public enum Kind {
    LOCKED,
    LOCATION_INSIDE_REPOSITORY,
    PRAGMA_REFUSED,
    STORAGE
  }

  private final Kind kind;

  public CacheUnavailableException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public Kind kind() {
    return kind;
  }
}
