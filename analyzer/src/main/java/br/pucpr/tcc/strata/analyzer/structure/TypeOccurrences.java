package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

final class TypeOccurrences {

  private TypeOccurrences() {}

  static Map<DependencyType, Long> checked(Map<DependencyType, Long> types, String owner) {
    Objects.requireNonNull(types, "types");
    if (types.isEmpty()) {
      throw new IllegalArgumentException("pelo menos um tipo de dependência: " + owner);
    }
    for (final Map.Entry<DependencyType, Long> entry : types.entrySet()) {
      if (!entry.getKey().isTypeEdge() || entry.getValue() == null || entry.getValue() < 1) {
        throw new IllegalArgumentException("um tipo presente tem >= 1 ocorrência: " + entry);
      }
    }
    return Collections.unmodifiableMap(new EnumMap<>(types));
  }
}
