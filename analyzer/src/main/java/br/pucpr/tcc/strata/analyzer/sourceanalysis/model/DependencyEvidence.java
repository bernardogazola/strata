package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record DependencyEvidence(
    CodeElementId fromMethod, CodeElementId toMethod, int line, ResolutionQuality resolution) {

  public DependencyEvidence {
    Objects.requireNonNull(resolution, "resolution");
    if (fromMethod != null && fromMethod.kind() != ElementKind.METHOD) {
      throw new IllegalArgumentException("fromMethod não é um método: " + fromMethod);
    }
    if (toMethod != null && toMethod.kind() != ElementKind.METHOD) {
      throw new IllegalArgumentException("toMethod não é um método: " + toMethod);
    }
    if (line < 0) {
      throw new IllegalArgumentException("não é uma linha: " + line);
    }
  }
}
