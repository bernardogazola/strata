package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.SearchHit;
import java.util.List;
import java.util.Objects;

public record SearchResultView(List<Hit> results) {

  public static final SearchResultView NONE = new SearchResultView(List.of());

  public SearchResultView {
    results = List.copyOf(Objects.requireNonNull(results, "results"));
  }

  public static SearchResultView of(List<SearchHit> hits) {
    throw new UnsupportedOperationException("ainda não implementado: graph.search (no fio)");
  }

  public record Hit(
      String elementId, ElementKind kind, String label, String packageId, String qualifiedName) {}
}
