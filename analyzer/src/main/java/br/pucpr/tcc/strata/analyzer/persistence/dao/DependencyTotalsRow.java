package br.pucpr.tcc.strata.analyzer.persistence.dao;

public record DependencyTotalsRow(int in, int out, Integer outExternal) {

  public DependencyTotalsRow {
    if (in < 0 || out < 0 || (outExternal != null && outExternal < 0)) {
      throw new IllegalArgumentException(
          "uma contagem negativa: " + in + "/" + out + "/" + outExternal);
    }
  }
}
