package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

public final class LocCounter {

  private final int[] codeLinePrefix;

  private LocCounter(int[] codeLinePrefix) {
    this.codeLinePrefix = codeLinePrefix;
  }

  public static LocCounter of(char[] text) {
    return new LocCounter(LineClassifier.codeLinePrefix(text));
  }

  public int lines() {
    return codeLinePrefix.length - 1;
  }

  public int loc() {
    return codeLinePrefix[lines()];
  }

  public int loc(int startLine, int endLine) {
    if (startLine < 1 || endLine > lines() || endLine < startLine) {
      throw new IllegalArgumentException(
          "as linhas "
              + startLine
              + ".."
              + endLine
              + " estão fora de um texto de "
              + lines()
              + " linha(s)");
    }
    return codeLinePrefix[endLine] - codeLinePrefix[startLine - 1];
  }
}
