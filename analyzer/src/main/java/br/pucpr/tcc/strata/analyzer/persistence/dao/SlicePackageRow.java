package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record SlicePackageRow(
    long pk,
    CodeElementId elementId,
    String qualifiedName,
    long loc,
    int typeCount,
    int fanIn,
    int fanOut,
    double pagerank,
    long resolvedOccurrences,
    long inferredOccurrences,
    long unresolvedExternalOccurrences) {

  public SlicePackageRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (elementId.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("não é um pacote: " + elementId);
    }
    if (loc < 0 || typeCount < 1 || fanIn < 0 || fanOut < 0) {
      throw new IllegalArgumentException("contagens de pacote inconsistentes: " + elementId);
    }
    if (resolvedOccurrences < 0 || inferredOccurrences < 0 || unresolvedExternalOccurrences < 0) {
      throw new IllegalArgumentException("ocorrências negativas: " + elementId);
    }
  }
}
