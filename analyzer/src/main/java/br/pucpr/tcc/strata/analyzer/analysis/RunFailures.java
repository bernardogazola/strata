package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.util.Objects;

public final class RunFailures {

  private RunFailures() {}

  public static Verdict classify(
      Throwable original, boolean runCancelled, Throwable siblingFailure) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public sealed interface Verdict permits Cancelled, Failed {

    boolean corruptCache();
  }

  public record Cancelled(boolean corruptCache) implements Verdict {}

  public record Failed(ProtocolError error, boolean corruptCache) implements Verdict {

    public Failed {
      Objects.requireNonNull(error, "error");
    }
  }
}
