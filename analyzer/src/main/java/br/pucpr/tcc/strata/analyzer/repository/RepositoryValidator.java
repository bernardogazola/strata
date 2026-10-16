package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.Objects;

public final class RepositoryValidator {

  private final RepositoryLimits limits;
  private final ProjectLayoutDetector layoutDetector;

  public RepositoryValidator() {
    this(RepositoryLimits.PRODUCTION);
  }

  RepositoryValidator(RepositoryLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.layoutDetector = new ProjectLayoutDetector(limits);
  }

  public RepositoryOutcome<RepositorySnapshot> inspect(Path claimedRoot) {
    throw new UnsupportedOperationException(
        "ainda não implementado: RepositoryValidator.inspect " + claimedRoot);
  }

  public RepositoryOutcome<ValidatedRepository> validate(Path claimedRoot) {
    throw new UnsupportedOperationException(
        "ainda não implementado: RepositoryValidator.validate "
            + claimedRoot
            + limits
            + layoutDetector);
  }
}
