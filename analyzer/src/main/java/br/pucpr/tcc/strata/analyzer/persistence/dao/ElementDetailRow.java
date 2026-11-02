package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.Objects;

public record ElementDetailRow(
    long pk,
    CodeElementId elementId,
    TypeKind typeKind,
    String simpleName,
    String qualifiedName,
    String modifiers,
    boolean isExternal,
    boolean isTest,
    CodeElementId packageId,
    String packageQualifiedName,
    String filePath,
    Integer startLine,
    Integer endLine) {

  public ElementDetailRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(modifiers, "modifiers");
    if (pk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + pk);
    }
    if ((packageId == null) != (packageQualifiedName == null)
        || (packageId != null && packageId.kind() != ElementKind.PACKAGE)) {
      throw new IllegalArgumentException("um id de pacote e o seu nome vêm juntos: " + elementId);
    }
    if ((startLine == null) != (endLine == null)) {
      throw new IllegalArgumentException("start_line e end_line são null juntos: " + elementId);
    }
  }

  public ElementKind kind() {
    return elementId.kind();
  }
}
