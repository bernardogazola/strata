package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record DependencyEndRow(
    long pk,
    CodeElementId elementId,
    String qualifiedName,
    boolean isExternal,
    CodeElementId packageId,
    String packageQualifiedName) {

  public DependencyEndRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (pk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + pk);
    }
    final ElementKind kind = elementId.kind();
    if (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE) {
      throw new IllegalArgumentException(
          "uma ponta de dependência é PACKAGE ou TYPE: " + elementId);
    }
    final boolean declaredPackage = kind == ElementKind.TYPE && !isExternal;
    if ((isExternal && kind != ElementKind.TYPE)
        || (packageId != null) != declaredPackage
        || (packageId == null) != (packageQualifiedName == null)
        || (packageId != null && packageId.kind() != ElementKind.PACKAGE)) {
      throw new IllegalArgumentException(
          "um pacote só cabe em uma ponta TYPE interna: " + elementId);
    }
  }
}
