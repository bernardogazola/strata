package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;

public record RepositoryIdentity(
    RepositoryKey key, PathFingerprint pathFingerprint, String rootCommitId) {

  public RepositoryIdentity {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(pathFingerprint, "pathFingerprint");
    RepositoryKey.requireRootCommitId(rootCommitId);
  }

  public static RepositoryIdentity of(
      String repositoryPath, PathFlavor flavor, String rootCommitId) {
    return new RepositoryIdentity(
        RepositoryKey.derive(repositoryPath, flavor, rootCommitId),
        PathFingerprint.derive(repositoryPath, flavor),
        rootCommitId);
  }
}
