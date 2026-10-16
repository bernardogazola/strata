package br.pucpr.tcc.strata.analyzer.repository;

import java.util.List;
import java.util.Objects;

public record RepositoryInventory(
    int totalFileCount, List<JavaSource> javaSources, List<BuildFileEntry> buildFiles) {

  public RepositoryInventory {
    javaSources = List.copyOf(Objects.requireNonNull(javaSources, "javaSources"));
    buildFiles = List.copyOf(Objects.requireNonNull(buildFiles, "buildFiles"));
  }
}
