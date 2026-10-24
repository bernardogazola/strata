package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import java.util.Objects;

public record TypeDependencyRow(Dependency dependency, boolean ambiguous, DependencyEndRow other) {

  public TypeDependencyRow {
    Objects.requireNonNull(dependency, "dependency");
    Objects.requireNonNull(other, "other");
    if (!other.elementId().equals(dependency.source())
        && !other.elementId().equals(dependency.target())) {
      throw new IllegalArgumentException(
          "a outra ponta é uma ponta da linha: " + other.elementId());
    }
  }
}
