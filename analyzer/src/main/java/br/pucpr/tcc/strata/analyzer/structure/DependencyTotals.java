package br.pucpr.tcc.strata.analyzer.structure;

public record DependencyTotals(int in, int out, Integer outExternal) {

  public DependencyTotals {
    if (in < 0 || out < 0 || (outExternal != null && outExternal < 0)) {
      throw new IllegalArgumentException(
          "um total negativo: " + in + "/" + out + "/" + outExternal);
    }
  }
}
