package br.pucpr.tcc.strata.analyzer.graph;

import java.util.Arrays;

public final class PageRankVector {

  private final double[] scores;
  private final boolean converged;

  public PageRankVector(double[] scores, boolean converged) {
    this.scores = scores.clone();
    this.converged = converged;
  }

  public int size() {
    return scores.length;
  }

  public double score(int vertex) {
    return scores[vertex];
  }

  public boolean converged() {
    return converged;
  }

  public double[] toArray() {
    return scores.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PageRankVector vector
        && converged == vector.converged
        && Arrays.equals(scores, vector.scores);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(scores) + Boolean.hashCode(converged);
  }

  @Override
  public String toString() {
    return "PageRankVector[size=" + scores.length + ", converged=" + converged + "]";
  }
}
