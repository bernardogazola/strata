package br.pucpr.tcc.strata.analyzer.repository;

import java.util.Objects;

public record ValidatedRepository(RepositorySnapshot snapshot, ProjectLayout layout) {

  public ValidatedRepository {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(layout, "layout");
    if (layout.sourceRoots().isEmpty()) {
      throw new IllegalArgumentException(
          "um repositório validado tem pelo menos uma raiz de código");
    }
  }
}
