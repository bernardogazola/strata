package br.pucpr.tcc.strata.analyzer.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record PathFingerprint(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^[0-9a-f]{64}$");
  private static final byte[] DOMAIN =
      "strata-path-fingerprint\0v1\0".getBytes(StandardCharsets.UTF_8);

  public PathFingerprint {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("não é uma impressão digital de caminho: " + value);
    }
  }

  public static PathFingerprint derive(String repositoryPath, PathFlavor flavor) {
    final byte[] path = CanonicalPath.bytes(repositoryPath, flavor);
    final MessageDigest sha256 = CanonicalPath.sha256();
    sha256.update(DOMAIN);
    return new PathFingerprint(HexFormat.of().formatHex(sha256.digest(path)));
  }
}
