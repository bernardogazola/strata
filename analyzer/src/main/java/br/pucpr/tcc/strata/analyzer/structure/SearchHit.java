package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record SearchHit(
    CodeElementId elementId,
    ElementKind kind,
    String label,
    String qualifiedName,
    CodeElementId packageId) {

  public SearchHit {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (kind != elementId.kind() || (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE)) {
      throw new IllegalArgumentException("um resultado de busca é PACKAGE ou TYPE: " + elementId);
    }
    final boolean packageIdFits =
        kind == ElementKind.PACKAGE
            ? packageId == null
            : packageId != null && packageId.kind() == ElementKind.PACKAGE;
    if (!packageIdFits) {
      throw new IllegalArgumentException(
          "packageId é o pacote de um TYPE, e null nos outros casos");
    }
  }
}
