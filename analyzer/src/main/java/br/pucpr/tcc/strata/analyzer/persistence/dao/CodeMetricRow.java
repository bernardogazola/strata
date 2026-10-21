package br.pucpr.tcc.strata.analyzer.persistence.dao;

public record CodeMetricRow(
    long elementPk, int loc, Integer unitCount, Integer ccSum, Integer ccMax, Integer ccP90) {

  public CodeMetricRow {
    if (loc < 0
        || (unitCount != null && unitCount < 0)
        || (ccMax != null && ccMax < 0)
        || (ccP90 != null && ccP90 < 0)) {
      throw new IllegalArgumentException("uma contagem negativa na métrica de " + elementPk);
    }
    if ((ccSum == null) != (ccMax == null)) {
      throw new IllegalArgumentException("cc_sum e cc_max são null juntos: " + elementPk);
    }
    if (ccSum != null
        && (unitCount == null
            || ccSum < unitCount
            || ccMax > ccSum
            || (long) ccMax * unitCount < ccSum)) {
      throw new IllegalArgumentException("não é um agregado de CC: a métrica de " + elementPk);
    }
    if (ccP90 != null && (ccMax == null || ccP90 > ccMax || (ccP90 == 0) != (unitCount == 0))) {
      throw new IllegalArgumentException("não é um percentil de CC: a métrica de " + elementPk);
    }
  }
}
