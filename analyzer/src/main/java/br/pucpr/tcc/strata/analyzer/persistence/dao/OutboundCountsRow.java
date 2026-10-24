package br.pucpr.tcc.strata.analyzer.persistence.dao;

public record OutboundCountsRow(int out, int outExternal) {

  public OutboundCountsRow {
    if (out < 0 || outExternal < 0) {
      throw new IllegalArgumentException("uma contagem negativa: " + out + "/" + outExternal);
    }
  }
}
