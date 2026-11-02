package br.pucpr.tcc.strata.analyzer.structure;

public record ElementMetrics(
    long loc, long unitCount, long ccSum, int ccMax, double ccMean, Integer ccP90) {

  public ElementMetrics {
    if (loc < 0 || unitCount < 0 || ccSum < 0 || ccMax < 0 || (ccP90 != null && ccP90 < 0)) {
      throw new IllegalArgumentException("uma métrica de código negativa");
    }
    if (!Double.isFinite(ccMean) || ccMean < 0d) {
      throw new IllegalArgumentException("ccMean é um número finito e não negativo: " + ccMean);
    }
  }
}
