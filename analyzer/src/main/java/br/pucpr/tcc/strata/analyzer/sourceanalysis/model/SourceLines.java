package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

public record SourceLines(int start, int end) {

  public static final SourceLines UNAVAILABLE = new SourceLines(0, 0);

  public SourceLines {
    final boolean unavailable = start == 0 && end == 0;
    if (!unavailable && (start < 1 || end < start)) {
      throw new IllegalArgumentException("não é um intervalo de linhas: " + start + ".." + end);
    }
  }

  public boolean available() {
    return start != 0;
  }
}
