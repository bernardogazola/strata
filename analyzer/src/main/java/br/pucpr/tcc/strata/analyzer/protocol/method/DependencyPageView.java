package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.DependencyEntry;
import br.pucpr.tcc.strata.analyzer.structure.DependencyPage;
import br.pucpr.tcc.strata.analyzer.structure.DependencyTotals;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record DependencyPageView(List<Item> items, String nextCursor, Totals totals) {

  public DependencyPageView {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    Objects.requireNonNull(totals, "totals");
  }

  public static DependencyPageView of(DependencyPage page) {
    final DependencyTotals totals = page.totals();
    return new DependencyPageView(
        page.items().stream().map(DependencyPageView::item).toList(),
        page.next() == null ? null : page.next().encode(),
        new Totals(totals.in(), totals.out(), totals.outExternal()));
  }

  private static Item item(DependencyEntry entry) {
    return new Item(
        entry.ambiguous(),
        entry.edgeId(),
        entry.elementId().value(),
        entry.isExternal(),
        entry.kind(),
        entry.label(),
        entry.packageId() == null ? null : entry.packageId().value(),
        entry.qualifiedName(),
        entry.resolution(),
        entry.types(),
        entry.weight());
  }

  public record Item(
      boolean ambiguous,
      String edgeId,
      String elementId,
      @JsonProperty("isExternal") boolean isExternal,
      ElementKind kind,
      String label,
      String packageId,
      String qualifiedName,
      ResolutionQuality resolution,
      Map<DependencyType, Long> types,
      long weight) {}

  public record Totals(int in, int out, Integer outExternal) {}
}
