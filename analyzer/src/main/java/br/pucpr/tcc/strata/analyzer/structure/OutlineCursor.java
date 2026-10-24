package br.pucpr.tcc.strata.analyzer.structure;

public record OutlineCursor(long elementPk) {

  public static final byte VERSION = 1;

  public OutlineCursor {
    if (elementPk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + elementPk);
    }
  }

  public String encode() {
    throw new UnsupportedOperationException("ainda não implementado: o cursor");
  }

  public static OutlineCursor decode(String wire) {
    throw new UnsupportedOperationException("ainda não implementado: o cursor");
  }
}
