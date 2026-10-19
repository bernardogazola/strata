package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class DependencyAggregator {

  private static final Comparator<Pair> EDGE_ORDER =
      Comparator.comparing(Pair::source).thenComparing(Pair::target);

  private DependencyAggregator() {}

  public static int weightOf(DependencyType type) {
    return switch (Objects.requireNonNull(type, "type")) {
      case EXTENDS, IMPLEMENTS -> 4;
      case FIELD_TYPE -> 3;
      case CALLS, RETURN_TYPE -> 2;
      case PARAMETER_TYPE, ANNOTATION_REFERENCE, IMPORTS -> 1;
      case MODULE_DEPENDENCY ->
          throw new IllegalArgumentException("MODULE_DEPENDENCY nunca é uma aresta de tipo");
    };
  }

  public static int rowWeight(Dependency row) {
    return Math.multiplyExact(weightOf(row.type()), row.occurrences());
  }

  public static List<DependencyEdge> aggregate(Collection<Dependency> rows) {
    final Map<Pair, Map<DependencyType, Dependency>> byPair = new TreeMap<>(EDGE_ORDER);
    for (final Dependency row : rows) {
      final Map<DependencyType, Dependency> types =
          byPair.computeIfAbsent(
              new Pair(row.source(), row.target()), pair -> new EnumMap<>(DependencyType.class));
      if (types.putIfAbsent(row.type(), row) != null) {
        throw new IllegalArgumentException(
            "duas linhas para " + row.source() + " -> " + row.target() + " " + row.type());
      }
    }
    final List<DependencyEdge> edges = new ArrayList<>(byPair.size());
    byPair.forEach((pair, types) -> edges.add(edge(pair, types)));
    return List.copyOf(edges);
  }

  private static DependencyEdge edge(Pair pair, Map<DependencyType, Dependency> rows) {
    final Map<DependencyType, Integer> types = new EnumMap<>(DependencyType.class);
    ResolutionQuality resolution = ResolutionQuality.RESOLVED;
    int occurrences = 0;
    int weight = 0;
    for (final Dependency row : rows.values()) {
      types.put(row.type(), row.occurrences());
      resolution = ResolutionQuality.weakest(resolution, row.resolution());
      occurrences = Math.addExact(occurrences, row.occurrences());
      weight = Math.addExact(weight, rowWeight(row));
    }
    return new DependencyEdge(
        pair.source(), pair.target(), types, types.size(), occurrences, resolution, weight);
  }

  private record Pair(CodeElementId source, CodeElementId target) {}
}
