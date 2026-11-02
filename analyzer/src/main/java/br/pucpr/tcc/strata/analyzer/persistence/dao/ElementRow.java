package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.NameFold;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.Objects;

public record ElementRow(
    long pk,
    CodeElementId elementId,
    ElementKind kind,
    Long parentPk,
    String simpleName,
    String qualifiedName,
    String sortKey,
    TypeKind typeKind,
    String modifiers,
    boolean isExternal,
    boolean isTest,
    Long sourceRootPk,
    Long filePk,
    Integer startLine,
    Integer endLine) {

  public ElementRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(sortKey, "sortKey");
    Objects.requireNonNull(modifiers, "modifiers");
    if (kind != elementId.kind()) {
      throw new IllegalArgumentException("uma linha " + kind + " para o id " + elementId);
    }
    if (typeKind != null && kind != ElementKind.TYPE) {
      throw new IllegalArgumentException("só um TYPE tem type_kind: " + elementId);
    }
    if ((startLine == null) != (endLine == null)) {
      throw new IllegalArgumentException("start_line e end_line são null juntos: " + elementId);
    }
  }

  public String searchName() {
    final boolean searchable = kind == ElementKind.PACKAGE || kind == ElementKind.TYPE;
    return searchable && !isExternal ? NameFold.fold(simpleName) : null;
  }
}
