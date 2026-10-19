package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import java.util.Objects;

public record SourceRootRow(
    long pk,
    SourceRootId sourceRootId,
    String relativePath,
    SourceRootKind kind,
    int javaFileCount) {

  public SourceRootRow {
    Objects.requireNonNull(sourceRootId, "sourceRootId");
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(kind, "kind");
  }
}
