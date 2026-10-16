package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

final class BuildSystemDetector {

  static final Set<String> BUILD_FILE_NAMES =
      Set.of(
          "pom.xml",
          "settings.gradle",
          "settings.gradle.kts",
          "build.gradle",
          "build.gradle.kts",
          "build.xml",
          "BUILD.bazel",
          "WORKSPACE",
          "WORKSPACE.bazel",
          "MODULE.bazel",
          "build.sbt");

  private final RepositoryLimits limits;

  BuildSystemDetector(RepositoryLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  BuildDescription detect(Path root, RepositoryInventory inventory) {
    throw new UnsupportedOperationException(
        "ainda não implementado: BuildSystemDetector " + limits + root + inventory);
  }
}
