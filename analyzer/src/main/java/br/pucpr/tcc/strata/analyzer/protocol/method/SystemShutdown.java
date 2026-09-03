package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SystemShutdown implements MethodHandler<MethodHandler.EmptyParams> {

  private final AtomicBoolean requested;

  public SystemShutdown(AtomicBoolean requested) {
    this.requested = Objects.requireNonNull(requested, "requested");
  }

  @Override
  public String method() {
    return "system.shutdown";
  }

  @Override
  public Class<MethodHandler.EmptyParams> paramsType() {
    return MethodHandler.EmptyParams.class;
  }

  @Override
  public Object handle(MethodHandler.EmptyParams params, Envelope.Request request) {
    requested.set(true);
    return Map.of();
  }
}
