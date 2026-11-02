package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.OutlinePage;
import java.util.List;
import java.util.Objects;

public record OutlinePageView(List<Item> items, String nextCursor) {

  public OutlinePageView {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
  }

  public static OutlinePageView of(OutlinePage page) {
    return new OutlinePageView(
        page.items().stream()
            .map(item -> new Item(item.childCount(), item.id().value(), item.kind(), item.label()))
            .toList(),
        page.next() == null ? null : page.next().encode());
  }

  public record Item(int childCount, String id, ElementKind kind, String label) {}
}
