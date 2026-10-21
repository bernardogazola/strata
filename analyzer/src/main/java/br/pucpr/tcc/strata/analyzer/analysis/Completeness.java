package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisCounters;

public record Completeness(
    int totalFiles,
    int skippedFiles,
    int parseFailures,
    boolean shallowClone,
    Integer historyTruncatedAtCommits) {

  private static final Completeness NONE = new Completeness(0, 0, 0, false, null);

  public Completeness {
    if (parseFailures < 0 || parseFailures > skippedFiles || skippedFiles > totalFiles) {
      throw new IllegalArgumentException(
          "os contadores devem respeitar 0 <= parseFailures <= skippedFiles <= totalFiles: "
              + parseFailures
              + " / "
              + skippedFiles
              + " / "
              + totalFiles);
    }
    if (historyTruncatedAtCommits != null && historyTruncatedAtCommits < 0) {
      throw new IllegalArgumentException(
          "historyTruncatedAtCommits deve ser >= 0: " + historyTruncatedAtCommits);
    }
  }

  public static Completeness none() {
    return NONE;
  }

  public AnalysisCounters toCounters() {
    return new AnalysisCounters(
        totalFiles, skippedFiles, parseFailures, shallowClone, historyTruncatedAtCommits);
  }
}
