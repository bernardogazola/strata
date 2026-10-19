package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record PackageElement(CodeElementId id, String simpleName, String qualifiedName)
    implements CodeElement {

  public PackageElement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    if (id.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("não é um id de pacote: " + id);
    }
  }
}
