package br.pucpr.tcc.strata.analyzer.repository;

import java.util.List;
import java.util.Objects;

public record ProjectLayout(
    BuildSystem buildSystem, List<String> declaredModules, List<SourceRoot> sourceRoots) {

  public ProjectLayout {
    Objects.requireNonNull(buildSystem, "buildSystem");
    declaredModules = List.copyOf(Objects.requireNonNull(declaredModules, "declaredModules"));
    sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots"));
  }
}
