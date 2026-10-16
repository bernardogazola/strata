package br.pucpr.tcc.strata.analyzer.repository;

import java.util.Objects;

public record SourceRoot(
    SourceRootId id, String relativePath, SourceRootKind kind, int javaFileCount) {

  public SourceRoot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(kind, "kind");
    if (javaFileCount < 1) {
      throw new IllegalArgumentException("uma raiz de código tem pelo menos um arquivo .java");
    }
  }
}
