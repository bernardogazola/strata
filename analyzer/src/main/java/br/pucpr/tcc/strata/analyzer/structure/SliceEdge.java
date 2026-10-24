package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Map;
import java.util.Objects;

public record SliceEdge(
    String id,
    String source,
    String target,
    Map<DependencyType, Long> types,
    long weight,
    ResolutionQuality resolution) {

  public SliceEdge {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(types, "types");
    Objects.requireNonNull(resolution, "resolution");
    if (!EdgeIds.isEdgeId(id)) {
      throw new IllegalArgumentException("não é um id de aresta: " + id);
    }
    if (source.equals(target)) {
      throw new IllegalArgumentException("uma aresta do recorte liga dois nós: " + source);
    }
    types = TypeOccurrences.checked(types, id);
    if (weight < 1) {
      throw new IllegalArgumentException("uma aresta pesa pelo menos 1: " + id);
    }
    if (resolution == ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException(
          "uma aresta do recorte nunca é UNRESOLVED_EXTERNAL: " + id);
    }
  }
}
