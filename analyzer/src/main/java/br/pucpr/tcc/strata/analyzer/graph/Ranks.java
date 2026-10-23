package br.pucpr.tcc.strata.analyzer.graph;

final class Ranks {

  private Ranks() {}

  static boolean isRank(double value) {
    return Double.isFinite(value) && value > 0d && value <= 1d;
  }
}
