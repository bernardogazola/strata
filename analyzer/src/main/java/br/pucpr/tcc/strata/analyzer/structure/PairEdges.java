package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class PairEdges {

  private PairEdges() {}

  public static List<PairEdge> aggregate(Collection<PairRow> rows) {
    final Map<CodeElementId, List<PairRow>> bySource = new TreeMap<>();
    for (final PairRow row : Objects.requireNonNull(rows, "rows")) {
      final Dependency dependency = row.dependency();
      if (!dependency.source().equals(dependency.target())) {
        bySource.computeIfAbsent(dependency.source(), source -> new ArrayList<>()).add(row);
      }
    }
    final List<PairEdge> pairs = new ArrayList<>();
    for (final List<PairRow> group : bySource.values()) {
      final Set<CodeElementId> ambiguousTargets = new HashSet<>();
      final List<Dependency> dependencies = new ArrayList<>(group.size());
      for (final PairRow row : group) {
        dependencies.add(row.dependency());
        if (row.ambiguous()) {
          ambiguousTargets.add(row.dependency().target());
        }
      }
      for (final DependencyEdge edge : DependencyAggregator.aggregate(dependencies)) {
        pairs.add(
            new PairEdge(
                EdgeIds.of(edge.source().value(), edge.target().value()),
                edge.source(),
                edge.target(),
                widened(edge.types()),
                edge.weight(),
                edge.resolution(),
                ambiguousTargets.contains(edge.target())));
      }
    }
    return List.copyOf(pairs);
  }

  public static PairEdge packageEdge(
      CodeElementId source, CodeElementId target, PackageEdgeRow row) {
    return new PairEdge(
        EdgeIds.of(source.value(), target.value()),
        source,
        target,
        widened(row.occurrences()),
        row.typePairCount(),
        row.resolution(),
        false);
  }

  private static Map<DependencyType, Long> widened(Map<DependencyType, Integer> occurrences) {
    final Map<DependencyType, Long> types = new EnumMap<>(DependencyType.class);
    occurrences.forEach((type, count) -> types.put(type, count.longValue()));
    return types;
  }
}
