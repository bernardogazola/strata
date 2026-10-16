package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.List;
import java.util.Objects;

public record SourceAnalysis(
    List<SourceFile> files, List<SourceIssue> issues, Diagnostics diagnostics) {

  public SourceAnalysis {
    files = List.copyOf(Objects.requireNonNull(files, "files"));
    issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public int totalFiles() {
    return files.size();
  }

  public int skippedFiles() {
    return (int) files.stream().filter(file -> file.status() != SourceFileStatus.ANALYZED).count();
  }

  public int parseFailures() {
    return (int)
        files.stream().filter(file -> file.status() == SourceFileStatus.PARSE_FAILED).count();
  }

  public record Diagnostics(
      int batches,
      int splitBatches,
      int quarantinedBatches,
      int builds,
      long preParseMillis,
      long buildMillis,
      long longestBuildMillis,
      String longestBuildRoot,
      long visitMillis) {

    public Diagnostics {
      Objects.requireNonNull(longestBuildRoot, "longestBuildRoot");
    }
  }
}
