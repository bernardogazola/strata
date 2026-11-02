package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import java.util.List;
import java.util.Objects;

public record GraphSource(
    List<GraphTypeRow> types, List<GraphPackageRow> packages, List<Dependency> rows) {

  public GraphSource {
    types = List.copyOf(Objects.requireNonNull(types, "types"));
    packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
  }
}
