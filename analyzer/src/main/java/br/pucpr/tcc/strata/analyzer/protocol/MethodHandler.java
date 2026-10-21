package br.pucpr.tcc.strata.analyzer.protocol;

import java.util.Objects;

public interface MethodHandler<P> {

  String method();

  Class<P> paramsType();

  Object handle(P params, Envelope.Request request);

  record EmptyParams() {}

  record Reply(Object payload, Runnable afterResponse) {

    public Reply {
      Objects.requireNonNull(payload, "payload");
      Objects.requireNonNull(afterResponse, "afterResponse");
    }
  }
}
