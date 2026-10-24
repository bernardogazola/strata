package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record PackageDependencyRow(PackageEdgeRow edge, DependencyEndRow other) {

  public PackageDependencyRow {
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(other, "other");
    if (other.elementId().kind() != ElementKind.PACKAGE
        || (other.pk() != edge.sourcePk() && other.pk() != edge.targetPk())) {
      throw new IllegalArgumentException("a outra ponta é um pacote da aresta: " + other.pk());
    }
  }
}
