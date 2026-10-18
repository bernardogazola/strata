package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

public record RepositoryKey(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^[0-9a-f]{16}$");

  public RepositoryKey {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException("não é uma repositoryKey: " + value);
    }
  }

  public static RepositoryKey derive(
      String repositoryPath, PathFlavor flavor, String rootCommitId) {
    throw new UnsupportedOperationException("RepositoryKey.derive em implementação");
  }
}
