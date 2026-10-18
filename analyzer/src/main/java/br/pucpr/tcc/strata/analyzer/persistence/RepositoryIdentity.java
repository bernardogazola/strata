package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

public record RepositoryIdentity(
    RepositoryKey key, PathFingerprint pathFingerprint, String rootCommitId) {

  private static final Pattern COMMIT_ID = Pattern.compile("^[0-9a-f]{40}([0-9a-f]{24})?$");

  public RepositoryIdentity {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(pathFingerprint, "pathFingerprint");
    if (rootCommitId != null && !COMMIT_ID.matcher(rootCommitId).matches()) {
      throw new IllegalArgumentException("não é um id de commit: " + rootCommitId);
    }
  }

  public static RepositoryIdentity of(
      String repositoryPath, PathFlavor flavor, String rootCommitId) {
    return new RepositoryIdentity(
        RepositoryKey.derive(repositoryPath, flavor, rootCommitId),
        PathFingerprint.derive(repositoryPath, flavor),
        rootCommitId);
  }
}
