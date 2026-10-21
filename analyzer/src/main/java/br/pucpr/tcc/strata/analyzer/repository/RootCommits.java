package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class RootCommits {

  private RootCommits() {}

  public static Optional<String> firstParentRoot(
      HeadState head, Path root, BooleanSupplier cancelled) {
    Objects.requireNonNull(head, "head");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(cancelled, "cancelled");
    return Optional.empty();
  }
}
