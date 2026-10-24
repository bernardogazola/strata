package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record OutlineItem(CodeElementId id, ElementKind kind, String label, int childCount) {

  public OutlineItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    if (kind != id.kind() || (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE)) {
      throw new IllegalArgumentException("uma linha do outline é PACKAGE ou TYPE: " + id);
    }
    if (childCount < 0) {
      throw new IllegalArgumentException("uma contagem de filhos negativa: " + id);
    }
  }
}
