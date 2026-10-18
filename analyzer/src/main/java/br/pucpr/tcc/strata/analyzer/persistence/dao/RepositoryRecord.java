package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.util.Objects;

public record RepositoryRecord(
    String repositoryKey,
    String displayName,
    String pathFingerprint,
    String rootCommitId,
    String defaultBranch,
    boolean shallow,
    BuildSystem buildSystem,
    long firstSeenAt,
    long lastSeenAt) {

  public RepositoryRecord {
    Objects.requireNonNull(repositoryKey, "repositoryKey");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(pathFingerprint, "pathFingerprint");
    Objects.requireNonNull(buildSystem, "buildSystem");
  }
}
