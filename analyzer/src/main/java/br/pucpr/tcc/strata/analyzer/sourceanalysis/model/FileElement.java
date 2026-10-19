package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record FileElement(
    CodeElementId id,
    String simpleName,
    String qualifiedName,
    String relativePath,
    SourceRootId rootId,
    CodeElementId packageId,
    boolean isTest)
    implements CodeElement {

  public FileElement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(rootId, "rootId");
    if (id.kind() != ElementKind.FILE
        || (packageId != null && packageId.kind() != ElementKind.PACKAGE)) {
      throw new IllegalArgumentException(
          "não é um arquivo em um pacote: " + id + " / " + packageId);
    }
  }
}
