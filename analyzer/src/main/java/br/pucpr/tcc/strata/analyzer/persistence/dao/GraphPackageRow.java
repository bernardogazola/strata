package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record GraphPackageRow(long pk, CodeElementId id) {

  public GraphPackageRow {
    Objects.requireNonNull(id, "id");
    if (id.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("uma linha de pacote pede um id de pacote: " + id);
    }
  }
}
