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
    throw new UnsupportedOperationException("ainda não implementado: outline.children (no fio)");
  }

  public record Item(int childCount, String id, ElementKind kind, String label) {}
}
