package br.pucpr.tcc.strata.analyzer.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record RepositoryKey(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^[0-9a-f]{16}$");
  private static final Pattern COMMIT_ID = Pattern.compile("^[0-9a-f]{40}([0-9a-f]{24})?$");
  private static final byte[] DOMAIN = "strata-repo-key\0v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte TERMINATOR = 0;
  private static final byte[] NO_ROOT = new byte[0];

  private static final int KEY_BYTES = 8;

  public RepositoryKey {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("não é uma repositoryKey: " + value);
    }
  }

  public static RepositoryKey derive(
      String repositoryPath, PathFlavor flavor, String rootCommitId) {
    final byte[] path = CanonicalPath.bytes(repositoryPath, flavor);
    requireRootCommitId(rootCommitId);
    final byte[] root =
        rootCommitId == null ? NO_ROOT : rootCommitId.getBytes(StandardCharsets.US_ASCII);
    final MessageDigest sha256 = CanonicalPath.sha256();
    sha256.update(DOMAIN);
    sha256.update(path);
    sha256.update(TERMINATOR);
    sha256.update(root);
    sha256.update(TERMINATOR);
    return new RepositoryKey(HexFormat.of().formatHex(sha256.digest(), 0, KEY_BYTES));
  }

  static void requireRootCommitId(String rootCommitId) {
    if (rootCommitId != null && !COMMIT_ID.matcher(rootCommitId).matches()) {
      throw new IllegalArgumentException("não é um id de commit: " + rootCommitId);
    }
  }
}
