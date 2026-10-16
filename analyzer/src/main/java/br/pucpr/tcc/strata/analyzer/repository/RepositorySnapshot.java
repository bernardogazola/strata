package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.Objects;

public record RepositorySnapshot(
    Path root, String displayName, HeadState head, RepositoryInventory inventory) {

  public RepositorySnapshot {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(head, "head");
    Objects.requireNonNull(inventory, "inventory");
  }
}
