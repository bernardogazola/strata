package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record PackageEdge(
    CodeElementId source,
    CodeElementId target,
    int typePairCount,
    Map<DependencyType, Integer> occurrences,
    int occurrenceTotal,
    ResolutionQuality resolution) {

  public PackageEdge {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(resolution, "resolution");
    occurrences =
        Collections.unmodifiableMap(
            new EnumMap<>(Objects.requireNonNull(occurrences, "occurrences")));
    if (source.kind() != ElementKind.PACKAGE || target.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException(
          "não é uma aresta de pacotes: " + source + " -> " + target);
    }
    if (source.equals(target)) {
      throw new IllegalArgumentException(
          "uma aresta de um pacote para ele mesmo é coesão interna: " + source);
    }
    long sum = 0;
    for (final Map.Entry<DependencyType, Integer> entry : occurrences.entrySet()) {
      if (!entry.getKey().isTypeEdge() || entry.getValue() == null || entry.getValue() < 1) {
        throw new IllegalArgumentException("um tipo presente tem >= 1 ocorrência: " + entry);
      }
      sum += entry.getValue();
    }
    if (typePairCount < 1 || occurrenceTotal != sum || occurrenceTotal < typePairCount) {
      throw new IllegalArgumentException("contagens inconsistentes em " + source + " -> " + target);
    }
    if (resolution == ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException("uma aresta entre tipos internos nunca é externa");
    }
  }
}
