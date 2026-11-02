package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Map;
import java.util.Objects;

public record PairEdge(
    String id,
    CodeElementId source,
    CodeElementId target,
    Map<DependencyType, Long> types,
    long weight,
    ResolutionQuality resolution,
    boolean ambiguous) {

  public PairEdge {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(types, "types");
    Objects.requireNonNull(resolution, "resolution");
    if (!EdgeIds.isEdgeId(id)) {
      throw new IllegalArgumentException("não é um id de aresta: " + id);
    }
    if (source.equals(target)) {
      throw new IllegalArgumentException("um par liga dois elementos: " + source);
    }
    types = TypeOccurrences.checked(types, id);
    if (weight < 1) {
      throw new IllegalArgumentException("um par pesa pelo menos 1: " + id);
    }
    if (ambiguous && resolution != ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException("um par ambíguo é UNRESOLVED_EXTERNAL: " + id);
    }
  }
}
