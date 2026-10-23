package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record GraphTypeRow(long pk, CodeElementId id, long packagePk, CodeElementId packageId) {

  public GraphTypeRow {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(packageId, "packageId");
    if (id.kind() != ElementKind.TYPE || packageId.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException(
          "uma linha de tipo pede um id de tipo e um id de pacote: " + id);
    }
  }
}
