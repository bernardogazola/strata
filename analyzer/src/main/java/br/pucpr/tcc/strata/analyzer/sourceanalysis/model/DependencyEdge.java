package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record DependencyEdge(
    CodeElementId source,
    CodeElementId target,
    Map<DependencyType, Integer> types,
    int distinctTypeCount,
    int occurrenceTotal,
    ResolutionQuality resolution,
    int weight) {

  public DependencyEdge {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(resolution, "resolution");
    types = Collections.unmodifiableMap(new EnumMap<>(Objects.requireNonNull(types, "types")));
    if (types.isEmpty() || distinctTypeCount != types.size()) {
      throw new IllegalArgumentException("distinctTypeCount deve ser o número de tipos");
    }
  }
}
