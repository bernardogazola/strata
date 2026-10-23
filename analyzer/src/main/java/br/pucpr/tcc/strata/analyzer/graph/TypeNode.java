package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record TypeNode(CodeElementId id, CodeElementId packageId) {

  public TypeNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(packageId, "packageId");
    if (id.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException("um nó de tipo pede um id de tipo: " + id);
    }
    if (packageId.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("o pacote de um tipo pede um id de pacote: " + packageId);
    }
  }
}
