package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;
import java.util.Set;

public record MethodElement(
    CodeElementId id,
    String simpleName,
    String qualifiedName,
    CodeElementId declaringTypeId,
    String filePath,
    SourceRootId rootId,
    Set<Modifier> modifiers,
    boolean isTest,
    SourceLines lines)
    implements CodeElement {

  public MethodElement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(simpleName, "simpleName");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(declaringTypeId, "declaringTypeId");
    Objects.requireNonNull(filePath, "filePath");
    Objects.requireNonNull(rootId, "rootId");
    modifiers = Modifier.canonical(Objects.requireNonNull(modifiers, "modifiers"));
    Objects.requireNonNull(lines, "lines");
    if (id.kind() != ElementKind.METHOD || declaringTypeId.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException(
          "não é um método de um tipo: " + id + " / " + declaringTypeId);
    }
  }
}
