package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record SearchRow(
    CodeElementId elementId,
    String qualifiedName,
    CodeElementId packageId,
    String packageQualifiedName) {

  public SearchRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    final ElementKind kind = elementId.kind();
    if (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE) {
      throw new IllegalArgumentException("uma linha de busca é um pacote ou um tipo: " + elementId);
    }
    final boolean isType = kind == ElementKind.TYPE;
    if (isType != (packageId != null) || isType != (packageQualifiedName != null)) {
      throw new IllegalArgumentException("só um tipo traz o seu pacote: " + elementId);
    }
  }
}
