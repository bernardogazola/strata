package br.pucpr.tcc.strata.analyzer.protocol;

public interface MethodHandler<P> {

  String method();

  Class<P> paramsType();

  Object handle(P params, Envelope.Request request);

  record EmptyParams() {}
}
