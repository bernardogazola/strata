package br.pucpr.tcc.strata.analyzer.structure;

public record SliceBudget(int nodes, int edges) {

  public SliceBudget {
    if (nodes < 1 || edges < 1) {
      throw new IllegalArgumentException(
          "um orçamento de recorte é positivo: " + nodes + "/" + edges);
    }
  }

  public static SliceBudget of(GraphLevel level, Integer limit) {
    throw new UnsupportedOperationException("ainda não implementado: o limite de nós");
  }
}
