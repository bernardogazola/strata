package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.persistence.CacheRecovery;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.ElementWriter;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class RunFailures {

  private RunFailures() {}

  public static Verdict classify(
      Throwable original, boolean runCancelled, Throwable siblingFailure) {
    Objects.requireNonNull(original, "original");
    if (runCancelled) {
      return new Cancelled(isCorruption(original));
    }
    if (original instanceof CancellationException) {
      return siblingFailure == null ? defect(original) : classify(siblingFailure, false, null);
    }
    return failure(original);
  }

  private static Verdict failure(Throwable original) {
    return switch (original) {
      case StageFailedException refused -> new Failed(refused.error(), false);
      case ElementWriter.WriteFailedException wrapped -> database(wrapped.getCause());
      case SQLException database -> database(database);
      case CacheUnavailableException unavailable ->
          new Failed(ErrorCodes.cacheUnavailable(unavailable.kind()), false);
      default -> defect(original);
    };
  }

  private static Verdict database(SQLException failure) {
    return switch (CacheRecovery.classify(failure)) {
      case CORRUPT -> new Failed(ErrorCodes.analysisCacheCorrupted(), true);
      case ENVIRONMENT ->
          new Failed(ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE), false);
      case BUG -> defect(failure);
    };
  }

  static boolean isCorruption(Throwable original) {
    final Throwable database =
        original instanceof ElementWriter.WriteFailedException wrapped
            ? wrapped.getCause()
            : original;
    return database instanceof SQLException failure
        && CacheRecovery.classify(failure) == CacheRecovery.Verdict.CORRUPT;
  }

  private static Verdict defect(Throwable original) {
    return new Failed(ErrorCodes.internalUnexpected(original.getClass().getName()), false);
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
