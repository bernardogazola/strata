package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record PackageEdgeRow(
    long sourcePk,
    long targetPk,
    int typePairCount,
    int occurrenceTotal,
    ResolutionQuality resolution,
    Map<DependencyType, Integer> occurrences) {

  public PackageEdgeRow {
    Objects.requireNonNull(resolution, "resolution");
    occurrences =
        Collections.unmodifiableMap(
            new EnumMap<>(Objects.requireNonNull(occurrences, "occurrences")));
    if (sourcePk == targetPk) {
      throw new IllegalArgumentException("uma aresta de pacotes liga dois pacotes: " + sourcePk);
    }
    long sum = 0;
    for (final Map.Entry<DependencyType, Integer> entry : occurrences.entrySet()) {
      if (!entry.getKey().isTypeEdge() || entry.getValue() == null || entry.getValue() < 1) {
        throw new IllegalArgumentException("um tipo presente tem >= 1 ocorrência: " + entry);
      }
      sum += entry.getValue();
    }
    if (typePairCount < 1 || occurrenceTotal != sum || occurrenceTotal < typePairCount) {
      throw new IllegalArgumentException(
          "contagens inconsistentes na aresta " + sourcePk + " -> " + targetPk);
    }
    if (resolution == ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException("uma aresta entre tipos internos nunca é externa");
    }
  }
}
