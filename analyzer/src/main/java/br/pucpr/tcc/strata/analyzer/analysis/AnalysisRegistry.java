package br.pucpr.tcc.strata.analyzer.analysis;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class AnalysisRegistry implements AutoCloseable {

  private final RegistrySettings settings;

  public AnalysisRegistry(RegistrySettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public StartResult start(
      String repositoryPath, Path claimedRoot, AnalysisConfiguration configuration) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public CancelResult cancel(AnalysisId id) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public Optional<RunSnapshot> status(AnalysisId id) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public Optional<ReadableAnalysis> readable(AnalysisId id) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public record CancelResult(boolean cancelled, Runnable afterResponse) {

    private static final Runnable NOTHING = () -> {};

    private static final CancelResult ALREADY_REQUESTED = new CancelResult(true, NOTHING);

    private static final CancelResult REFUSED = new CancelResult(false, NOTHING);

    public CancelResult {
      Objects.requireNonNull(afterResponse, "afterResponse");
    }

    public static CancelResult requested(Runnable cancelToken) {
      return new CancelResult(true, cancelToken);
    }

    public static CancelResult alreadyRequested() {
      return ALREADY_REQUESTED;
    }

    public static CancelResult refused() {
      return REFUSED;
    }
  }
}
