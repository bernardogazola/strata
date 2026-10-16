package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.Objects;

public final class ProjectLayoutDetector {

  private final BuildSystemDetector buildSystemDetector;
  private final SourceRootDetector sourceRootDetector;

  public ProjectLayoutDetector() {
    this(RepositoryLimits.PRODUCTION);
  }

  ProjectLayoutDetector(RepositoryLimits limits) {
    Objects.requireNonNull(limits, "limits");
    this.buildSystemDetector = new BuildSystemDetector(limits);
    this.sourceRootDetector = new SourceRootDetector();
  }

  public ProjectLayout detect(Path root, RepositoryInventory inventory) {
    final BuildDescription build = buildSystemDetector.detect(root, inventory);
    return new ProjectLayout(
        build.buildSystem(),
        build.declaredModules(),
        sourceRootDetector.detect(root, inventory, build));
  }
}
