package br.pucpr.tcc.strata.analyzer.structure;

import java.util.Objects;

public record DependencyCursor(DependencyDirection direction, long anchorPk, long lastPk) {

  public static final byte VERSION = 2;

  public DependencyCursor {
    Objects.requireNonNull(direction, "direction");
    if (anchorPk < 1 || lastPk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + anchorPk + "/" + lastPk);
    }
  }

  public String encode() {
    throw new UnsupportedOperationException("ainda não implementado: o cursor de dependências");
  }

  public static DependencyCursor decode(String wire) {
    throw new UnsupportedOperationException("ainda não implementado: o cursor de dependências");
  }
}
