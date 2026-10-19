package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;
import java.util.Set;

public record TypeElement(
    CodeElementId id,
    String simpleName,
    String qualifiedName,
    TypeKind typeKind,
    CodeElementId packageId,
    String filePath,
    SourceRootId rootId,
    Set<Modifier> modifiers,
    boolean isTest,
    SourceLines lines)
    implements CodeElement {

  public TypeElement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(typeKind, "typeKind");
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(filePath, "filePath");
    Objects.requireNonNull(rootId, "rootId");
    modifiers = Modifier.canonical(Objects.requireNonNull(modifiers, "modifiers"));
    Objects.requireNonNull(lines, "lines");
    if (id.kind() != ElementKind.TYPE || packageId.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("não é um tipo em um pacote: " + id + " / " + packageId);
    }
  }
}
