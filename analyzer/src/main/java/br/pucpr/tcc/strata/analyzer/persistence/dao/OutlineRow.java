package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record OutlineRow(long pk, CodeElementId elementId, String qualifiedName, int childCount) {

  public OutlineRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    final ElementKind kind = elementId.kind();
    if (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE) {
      throw new IllegalArgumentException("o outline lista pacotes e tipos: " + elementId);
    }
    if (pk < 1 || childCount < 0) {
      throw new IllegalArgumentException("linha do outline inconsistente: " + elementId);
    }
  }
}
