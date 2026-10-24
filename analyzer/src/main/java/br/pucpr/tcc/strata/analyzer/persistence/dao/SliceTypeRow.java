package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record SliceTypeRow(
    long pk,
    CodeElementId elementId,
    String qualifiedName,
    long loc,
    int fanIn,
    int fanOut,
    double pagerank,
    long resolvedOccurrences,
    long inferredOccurrences,
    long unresolvedExternalOccurrences) {

  public SliceTypeRow {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (elementId.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException("não é um tipo: " + elementId);
    }
    if (loc < 0 || fanIn < 0 || fanOut < 0) {
      throw new IllegalArgumentException("contagens de tipo inconsistentes: " + elementId);
    }
    if (resolvedOccurrences < 0 || inferredOccurrences < 0 || unresolvedExternalOccurrences < 0) {
      throw new IllegalArgumentException("ocorrências negativas: " + elementId);
    }
  }
}
