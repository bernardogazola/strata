package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import java.util.Objects;

public record SourceFileRow(
    long pk, String relativePath, long sizeBytes, long mtimeMillis, SourceFileStatus status) {

  public SourceFileRow {
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(status, "status");
  }
}
