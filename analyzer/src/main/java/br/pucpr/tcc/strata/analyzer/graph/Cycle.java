package br.pucpr.tcc.strata.analyzer.graph;

public record Cycle(int id, int size) {

  public Cycle {
    if (id < 1 || size < 2) {
      throw new IllegalArgumentException("um ciclo tem id >= 1 e >= 2 membros: " + id + "/" + size);
    }
  }
}
