package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record EvidenceMethodRow(
    CodeElementId elementId,
    CodeElementId ownerId,
    String ownerSimpleName,
    String ownerQualifiedName,
    String ownerPackageQualifiedName) {

  public EvidenceMethodRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(ownerSimpleName, "ownerSimpleName");
    Objects.requireNonNull(ownerQualifiedName, "ownerQualifiedName");
    Objects.requireNonNull(ownerPackageQualifiedName, "ownerPackageQualifiedName");
    if (elementId.kind() != ElementKind.METHOD || ownerId.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException("um método de um tipo: " + elementId + " de " + ownerId);
    }
  }
}
