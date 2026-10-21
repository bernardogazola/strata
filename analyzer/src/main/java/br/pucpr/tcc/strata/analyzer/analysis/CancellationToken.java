package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class CancellationToken {

  private final CancellationToken parent;
  private volatile boolean cancelled;

  private CancellationToken(CancellationToken parent) {
    this.parent = parent;
  }

  public static CancellationToken create() {
    return new CancellationToken(null);
  }

  public CancellationToken child() {
    return new CancellationToken(this);
  }

  public void cancel() {
    cancelled = true;
  }

  public boolean isCancelled() {
    for (CancellationToken token = this; token != null; token = token.parent) {
      if (token.cancelled) {
        return true;
      }
    }
    return false;
  }

  public void throwIfCancelled() {
    if (isCancelled()) {
      throw new CancellationException("análise cancelada");
    }
  }

  public BooleanSupplier asSupplier() {
    return this::isCancelled;
  }
}
