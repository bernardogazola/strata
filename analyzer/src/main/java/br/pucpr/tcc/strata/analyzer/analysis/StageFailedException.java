package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.util.Objects;

public final class StageFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ProtocolError error;

  public StageFailedException(ProtocolError error) {
    super(Objects.requireNonNull(error, "error").code(), null, false, false);
    this.error = error;
  }

  public ProtocolError error() {
    return error;
  }
}
