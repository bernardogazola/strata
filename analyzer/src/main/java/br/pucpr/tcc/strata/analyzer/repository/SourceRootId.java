package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

public record SourceRootId(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^sr_[0-9a-hjkmnp-tv-z]{8}$");
  private static final String CROCKFORD_LOWER = "0123456789abcdefghjkmnpqrstvwxyz";
  private static final byte[] DOMAIN = "strata-source-root\0v1\0".getBytes(StandardCharsets.UTF_8);
  private static final int ID_CHARS = 8;
  private static final int BITS_PER_CHAR = 5;
  private static final int DIGEST_BYTES_USED = 5;

  public SourceRootId {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("não é um SourceRootId: " + value);
    }
  }

  public static SourceRootId derive(String relativePath) {
    Objects.requireNonNull(relativePath, "relativePath");
    final MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("todo JDK traz SHA-256", impossible);
    }
    sha256.update(DOMAIN);
    final byte[] digest = sha256.digest(relativePath.getBytes(StandardCharsets.UTF_8));
    long bits = 0;
    for (int index = 0; index < DIGEST_BYTES_USED; index++) {
      bits = (bits << Byte.SIZE) | (digest[index] & 0xFF);
    }
    final char[] encoded = new char[ID_CHARS];
    for (int index = ID_CHARS - 1; index >= 0; index--) {
      encoded[index] = CROCKFORD_LOWER.charAt((int) (bits & 0x1F));
      bits >>>= BITS_PER_CHAR;
    }
    return new SourceRootId("sr_" + new String(encoded));
  }
}
