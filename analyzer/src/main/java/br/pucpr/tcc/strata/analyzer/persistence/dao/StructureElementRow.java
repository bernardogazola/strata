package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record StructureElementRow(
    long pk, CodeElementId elementId, Long parentPk, String qualifiedName, boolean isExternal) {

  public StructureElementRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (pk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + pk);
    }
  }

  public ElementKind kind() {
    return elementId.kind();
  }
}
