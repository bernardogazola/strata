package br.pucpr.tcc.strata.analyzer.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SourceRoots {

  static final String REPOSITORY_ROOT = "";

  private SourceRoots() {}

  public static Optional<String> deepest(String filePath, Set<String> rootPaths) {
    Objects.requireNonNull(filePath, "filePath");
    Objects.requireNonNull(rootPaths, "rootPaths");
    String directory = parentOf(filePath);
    while (!rootPaths.contains(directory)) {
      if (directory.isEmpty()) {
        return Optional.empty();
      }
      directory = parentOf(directory);
    }
    return Optional.of(directory);
  }

  static String parentOf(String relativePath) {
    final int slash = relativePath.lastIndexOf('/');
    return slash < 0 ? REPOSITORY_ROOT : relativePath.substring(0, slash);
  }
}
