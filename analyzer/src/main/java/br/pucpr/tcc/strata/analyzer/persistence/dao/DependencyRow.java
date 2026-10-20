package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Objects;

public record DependencyRow(
    long pk,
    long sourcePk,
    long targetPk,
    DependencyType type,
    ResolutionQuality resolution,
    int occurrences,
    int weight,
    boolean ambiguous) {

  public DependencyRow {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resolution, "resolution");
    if (!type.isTypeEdge()) {
      throw new IllegalArgumentException("não é uma dependência do grafo de tipos: " + type);
    }
    if (occurrences < 1) {
      throw new IllegalArgumentException("uma linha tem pelo menos uma ocorrência: " + occurrences);
    }
  }
}
