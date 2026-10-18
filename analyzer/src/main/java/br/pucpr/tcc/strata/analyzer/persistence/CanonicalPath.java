package br.pucpr.tcc.strata.analyzer.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Objects;

final class CanonicalPath {

  private static final char WINDOWS_SEPARATOR = '\\';
  private static final char SEPARATOR = '/';
  private static final int ASCII_CASE_OFFSET = 'a' - 'A';

  private CanonicalPath() {}

  static byte[] bytes(String repositoryPath, PathFlavor flavor) {
    Objects.requireNonNull(repositoryPath, "repositoryPath");
    Objects.requireNonNull(flavor, "flavor");
    final String composed = Normalizer.normalize(repositoryPath, Normalizer.Form.NFC);
    final String text =
        flavor == PathFlavor.WINDOWS
            ? asciiLower(composed.replace(WINDOWS_SEPARATOR, SEPARATOR))
            : composed;
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("todo JDK traz SHA-256", impossible);
    }
  }

  private static String asciiLower(String text) {
    final char[] lowered = text.toCharArray();
    for (int index = 0; index < lowered.length; index++) {
      final char letter = lowered[index];
      if (letter >= 'A' && letter <= 'Z') {
        lowered[index] = (char) (letter + ASCII_CASE_OFFSET);
      }
    }
    return new String(lowered);
  }
}
