package br.pucpr.tcc.strata.analyzer.structure;

import java.util.Objects;
import java.util.Optional;

public record SearchTerm(String folded) {

  public SearchTerm {
    Objects.requireNonNull(folded, "folded");
    if (folded.isEmpty()) {
      throw new IllegalArgumentException("um termo de busca vazio casa com todas as linhas");
    }
  }

  public static Optional<SearchTerm> of(String query) {
    throw new UnsupportedOperationException("ainda não implementado: o termo de busca");
  }
}
