package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

public record PathFingerprint(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^[0-9a-f]{64}$");

  public PathFingerprint {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("não é uma impressão digital de caminho: " + value);
    }
  }

  public static PathFingerprint derive(String repositoryPath, PathFlavor flavor) {
    throw new UnsupportedOperationException("PathFingerprint.derive em implementação");
  }
}
