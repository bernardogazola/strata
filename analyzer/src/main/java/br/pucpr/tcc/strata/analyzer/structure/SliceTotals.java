package br.pucpr.tcc.strata.analyzer.structure;

public record SliceTotals(int nodes, int edges) {

  public SliceTotals {
    if (nodes < 0 || edges < 0) {
      throw new IllegalArgumentException(
          "os totais contam, nunca abaixo de 0: " + nodes + "/" + edges);
    }
  }
}
