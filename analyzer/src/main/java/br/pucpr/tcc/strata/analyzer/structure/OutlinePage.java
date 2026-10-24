package br.pucpr.tcc.strata.analyzer.structure;

import java.util.List;
import java.util.Objects;

public record OutlinePage(List<OutlineItem> items, OutlineCursor next) {

  public static final OutlinePage EMPTY = new OutlinePage(List.of(), null);

  public OutlinePage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (items.isEmpty() && next != null) {
      throw new IllegalArgumentException("uma página vazia não tem próximo cursor");
    }
  }
}
