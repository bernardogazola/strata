package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Objects;

public record RelationshipEvidence(
    DependencyType type,
    String filePath,
    Integer line,
    ResolutionQuality resolution,
    MethodRef fromMethod,
    MethodRef toMethod) {

  public RelationshipEvidence {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resolution, "resolution");
    if (!type.isTypeEdge()) {
      throw new IllegalArgumentException("não é uma dependência do grafo de tipos: " + type);
    }
    if (line != null && line < 1) {
      throw new IllegalArgumentException("uma linha é contada a partir de 1, ou null: " + line);
    }
    if (toMethod != null && type != DependencyType.CALLS) {
      throw new IllegalArgumentException(
          "só uma ocorrência CALLS nomeia um método chamado: " + type);
    }
  }
}
