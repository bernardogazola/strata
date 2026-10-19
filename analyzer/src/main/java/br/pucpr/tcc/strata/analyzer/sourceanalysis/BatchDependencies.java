package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import java.util.List;
import java.util.Objects;

public record BatchDependencies(
    List<ExtractedDependency> dependencies, List<ModuleDependency> modules) {

  public static final BatchDependencies NONE = new BatchDependencies(List.of(), List.of());

  public BatchDependencies {
    dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
  }

  public int rows() {
    int rows = dependencies.size() + modules.size();
    for (final ExtractedDependency dependency : dependencies) {
      rows += dependency.evidence().size();
    }
    return rows;
  }
}
