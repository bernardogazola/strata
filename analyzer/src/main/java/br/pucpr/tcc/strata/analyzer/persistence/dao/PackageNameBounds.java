package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.util.Objects;

public record PackageNameBounds(String first, String last) {

  public PackageNameBounds {
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(last, "last");
    if (first.isEmpty() || last.isEmpty()) {
      throw new IllegalArgumentException("o pacote padrão não é um limite");
    }
  }
}
