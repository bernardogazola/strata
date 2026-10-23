package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record GraphInput(List<TypeNode> types, List<Dependency> rows) {

  public GraphInput {
    types = List.copyOf(Objects.requireNonNull(types, "types"));
    rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    final Set<CodeElementId> seen = new HashSet<>();
    for (final TypeNode type : types) {
      if (!seen.add(type.id())) {
        throw new IllegalArgumentException("um tipo listado duas vezes: " + type.id());
      }
    }
  }
}
