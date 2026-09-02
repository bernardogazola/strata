package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.bootstrap.Uptime;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import java.util.Objects;

public final class SystemPing implements MethodHandler<MethodHandler.EmptyParams> {

  private final Uptime uptime;

  public SystemPing(Uptime uptime) {
    this.uptime = Objects.requireNonNull(uptime, "uptime");
  }

  @Override
  public String method() {
    return "system.ping";
  }

  @Override
  public Class<MethodHandler.EmptyParams> paramsType() {
    return MethodHandler.EmptyParams.class;
  }

  @Override
  public Object handle(MethodHandler.EmptyParams params, Envelope.Request request) {
    return new Pong(uptime.millis());
  }

  public record Pong(long uptimeMillis) {}
}
