package br.pucpr.tcc.strata.analyzer.persistence.dao;

public record AnalysisCounters(
    int totalFiles,
    int skippedFiles,
    int parseFailures,
    boolean shallowClone,
    Integer historyTruncatedAt) {

  public AnalysisCounters {
    if (parseFailures < 0 || parseFailures > skippedFiles || skippedFiles > totalFiles) {
      throw new IllegalArgumentException(
          "os contadores devem respeitar 0 <= parseFailures <= skippedFiles <= totalFiles: "
              + parseFailures
              + " / "
              + skippedFiles
              + " / "
              + totalFiles);
    }
  }

  public static AnalysisCounters none() {
    return new AnalysisCounters(0, 0, 0, false, null);
  }
}
