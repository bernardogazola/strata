package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record Dependency(
    CodeElementId source,
    CodeElementId target,
    DependencyType type,
    ResolutionQuality resolution,
    int occurrences) {

  public Dependency {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resolution, "resolution");
    if (source.kind() != ElementKind.TYPE || target.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException(
          "não é uma linha de tipo para tipo: " + source + " -> " + target);
    }
    if (!type.isTypeEdge()) {
      throw new IllegalArgumentException("não é uma dependência do grafo de tipos: " + type);
    }
    if (occurrences < 1) {
      throw new IllegalArgumentException("uma linha tem pelo menos uma ocorrência: " + occurrences);
    }
  }
}
