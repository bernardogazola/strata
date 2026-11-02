package br.pucpr.tcc.strata.analyzer.structure;

import java.util.List;
import java.util.Objects;

public record DependencyPage(
    List<DependencyEntry> items, DependencyCursor next, DependencyTotals totals) {

  public DependencyPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    Objects.requireNonNull(totals, "totals");
    if (items.isEmpty() && next != null) {
      throw new IllegalArgumentException("uma página vazia não tem próximo cursor");
    }
  }
}
