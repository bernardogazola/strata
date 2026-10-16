package br.pucpr.tcc.strata.analyzer.protocol;

import java.util.Objects;

public final class RequestRejected extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ProtocolError error;

  public RequestRejected(ProtocolError error) {
    super(Objects.requireNonNull(error, "error").code(), null, false, false);
    this.error = error;
  }

  public ProtocolError error() {
    return error;
  }
}
