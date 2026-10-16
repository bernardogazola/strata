package br.pucpr.tcc.strata.analyzer.repository;

import java.util.List;
import java.util.Objects;

public record BuildDescription(
    BuildSystem buildSystem,
    List<String> declaredModules,
    List<String> moduleDirectories,
    List<DeclaredSourceDirectory> declaredSourceDirectories) {

  public BuildDescription {
    Objects.requireNonNull(buildSystem, "buildSystem");
    declaredModules = List.copyOf(Objects.requireNonNull(declaredModules, "declaredModules"));
    moduleDirectories = List.copyOf(Objects.requireNonNull(moduleDirectories, "moduleDirectories"));
    declaredSourceDirectories =
        List.copyOf(Objects.requireNonNull(declaredSourceDirectories, "declaredSourceDirectories"));
  }

  public record DeclaredSourceDirectory(String relativePath, SourceRootKind kind) {

    public DeclaredSourceDirectory {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(kind, "kind");
    }
  }
}
