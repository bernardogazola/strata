package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record ElementMethodRow(
    CodeElementId elementId, Integer ccSum, Integer startLine, Integer endLine) {

  public ElementMethodRow {
    Objects.requireNonNull(elementId, "elementId");
    if (elementId.kind() != ElementKind.METHOD) {
      throw new IllegalArgumentException("não é um método: " + elementId);
    }
    if ((startLine == null) != (endLine == null)) {
      throw new IllegalArgumentException("start_line e end_line são null juntos: " + elementId);
    }
  }
}
