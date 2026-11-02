package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class StructuralGraphBuilder {

  private StructuralGraphBuilder() {}

  public static StructuralGraph build(
      GraphInput input, ExternalPackages packages, BooleanSupplier cancelled) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(packages, "packages");
    Objects.requireNonNull(cancelled, "cancelled");
    final List<TypeNode> types =
        input.types().stream().sorted(Comparator.comparing(TypeNode::id)).toList();
    final CodeElementId[] typeIds = types.stream().map(TypeNode::id).toArray(CodeElementId[]::new);
    final CodeElementId[] packageIds =
        types.stream().map(TypeNode::packageId).distinct().sorted().toArray(CodeElementId[]::new);
    final Map<CodeElementId, Integer> typeIndex = indexOf(typeIds);
    final Map<CodeElementId, Integer> packageIndex = indexOf(packageIds);
    final int[] packageOfType = new int[typeIds.length];
    for (int type = 0; type < typeIds.length; type++) {
      packageOfType[type] = packageIndex.get(types.get(type).packageId());
    }
    final List<List<Dependency>> rowsBySource = groupBySource(input.rows(), typeIndex);
    final Aggregation aggregation = new Aggregation(typeIds.length);
    for (int source = 0; source < typeIds.length; source++) {
      requireNotCancelled(cancelled);
      aggregation.addSource(source, rowsBySource.get(source), typeIndex, packages);
    }
    return new StructuralGraph(
        typeIds,
        packageOfType,
        packageIds,
        aggregation.edges,
        aggregation.fanOutExternal,
        aggregation.resolutionMixes(),
        aggregation.selfPairs,
        aggregation.denylistedPairs);
  }

  private static Map<CodeElementId, Integer> indexOf(CodeElementId[] ids) {
    final Map<CodeElementId, Integer> index = HashMap.newHashMap(ids.length);
    for (int position = 0; position < ids.length; position++) {
      index.put(ids[position], position);
    }
    return index;
  }

  private static List<List<Dependency>> groupBySource(
      List<Dependency> rows, Map<CodeElementId, Integer> typeIndex) {
    final List<List<Dependency>> groups = new ArrayList<>(typeIndex.size());
    for (int type = 0; type < typeIndex.size(); type++) {
      groups.add(new ArrayList<>());
    }
    for (final Dependency row : rows) {
      final Integer source = typeIndex.get(row.source());
      if (source == null) {
        throw new IllegalArgumentException(
            "uma linha de dependência a partir de um tipo fora da entrada: " + row.source());
      }
      groups.get(source).add(row);
    }
    return groups;
  }

  private static void requireNotCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException("grafo estrutural cancelado");
    }
  }

  private static final class Aggregation {

    private final TypeEdges edges = new TypeEdges();
    private final int[] fanOutExternal;
    private final long[] resolved;
    private final long[] inferred;
    private final long[] unresolvedExternal;
    private long selfPairs;
    private long denylistedPairs;

    private Aggregation(int typeCount) {
      this.fanOutExternal = new int[typeCount];
      this.resolved = new long[typeCount];
      this.inferred = new long[typeCount];
      this.unresolvedExternal = new long[typeCount];
    }

    private void addSource(
        int source,
        List<Dependency> rows,
        Map<CodeElementId, Integer> typeIndex,
        ExternalPackages packages) {
      if (rows.isEmpty()) {
        return;
      }
      for (final Dependency row : rows) {
        countResolution(source, row.resolution(), row.occurrences());
      }
      boolean selfPair = false;
      for (final DependencyEdge pair : DependencyAggregator.aggregate(rows)) {
        final Integer target = typeIndex.get(pair.target());
        if (target == null) {
          countExternal(source, pair.target(), packages);
        } else if (target == source) {
          selfPair = true;
        } else {
          edges.add(source, target, pair.resolution(), pair.types());
        }
      }
      if (selfPair) {
        selfPairs++;
      }
    }

    private void countResolution(int source, ResolutionQuality resolution, int occurrences) {
      switch (resolution) {
        case RESOLVED -> resolved[source] = Math.addExact(resolved[source], occurrences);
        case INFERRED -> inferred[source] = Math.addExact(inferred[source], occurrences);
        case UNRESOLVED_EXTERNAL ->
            unresolvedExternal[source] = Math.addExact(unresolvedExternal[source], occurrences);
      }
    }

    private ResolutionMix[] resolutionMixes() {
      final ResolutionMix[] mixes = new ResolutionMix[resolved.length];
      for (int type = 0; type < mixes.length; type++) {
        mixes[type] = new ResolutionMix(resolved[type], inferred[type], unresolvedExternal[type]);
      }
      return mixes;
    }

    private void countExternal(int source, CodeElementId target, ExternalPackages packages) {
      if (packages.isDenylisted(target)) {
        denylistedPairs++;
      } else {
        fanOutExternal[source]++;
      }
    }
  }
}
