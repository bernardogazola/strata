package br.pucpr.tcc.strata.analyzer.protocol;

import java.util.Map;
import java.util.Objects;

public record ProtocolError(String code, String detail, String origin, Map<String, Object> params) {

  public ProtocolError {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(origin, "origin");
    params = Map.copyOf(Objects.requireNonNull(params, "params"));
  }
}
