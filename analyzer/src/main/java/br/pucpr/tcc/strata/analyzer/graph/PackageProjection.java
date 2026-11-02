package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class PackageProjection {

  private static final DependencyType[] DEPENDENCY_TYPES = DependencyType.values();

  private static final int NONE = -1;

  private PackageProjection() {}

  public static PackageGraph project(StructuralGraph graph, BooleanSupplier cancelled) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(cancelled, "cancelled");
    final int[] internalCohesion = new int[graph.packageCount()];
    final SortedMap<Long, PairSum> crossing = fold(graph, internalCohesion);
    requireNotCancelled(cancelled);

    final List<PackageEdge> edges = new ArrayList<>(crossing.size());
    final int[] sources = new int[crossing.size()];
    final int[] targets = new int[crossing.size()];
    int position = 0;
    for (final Map.Entry<Long, PairSum> pair : crossing.entrySet()) {
      sources[position] = sourceOf(pair.getKey());
      targets[position] = targetOf(pair.getKey());
      edges.add(
          pair.getValue()
              .toEdge(graph.packageId(sources[position]), graph.packageId(targets[position])));
      position++;
    }
    final CodeElementId[] packageIds = new CodeElementId[graph.packageCount()];
    Arrays.setAll(packageIds, graph::packageId);
    requireNotCancelled(cancelled);

    final int[] externalFanIn = new int[graph.typeCount()];
    final int[] bridgeSpan = foreignPackagesReached(graph);
    final int[] reaching = foreignPackagesReaching(graph, externalFanIn);
    for (int type = 0; type < bridgeSpan.length; type++) {
      bridgeSpan[type] += reaching[type];
    }
    return new PackageGraph(
        packageIds,
        edges,
        internalCohesion,
        bridgeSpan,
        externalFanIn,
        DenseGraphs.forward(packageIds.length, sources, targets));
  }

  private static SortedMap<Long, PairSum> fold(StructuralGraph graph, int[] internalCohesion) {
    final SortedMap<Long, PairSum> crossing = new TreeMap<>();
    for (int edge = 0; edge < graph.edgeCount(); edge++) {
      final int from = graph.packageOf(graph.edgeSource(edge));
      final int to = graph.packageOf(graph.edgeTarget(edge));
      if (from == to) {
        internalCohesion[from]++;
      } else {
        crossing.computeIfAbsent(pairKey(from, to), unused -> new PairSum()).add(graph, edge);
      }
    }
    return crossing;
  }

  private static int[] foreignPackagesReached(StructuralGraph graph) {
    final int[] reached = new int[graph.typeCount()];
    final int[] lastSource = new int[graph.packageCount()];
    Arrays.fill(lastSource, NONE);
    for (int edge = 0; edge < graph.edgeCount(); edge++) {
      final int source = graph.edgeSource(edge);
      final int pkg = graph.packageOf(graph.edgeTarget(edge));
      if (pkg != graph.packageOf(source) && lastSource[pkg] != source) {
        lastSource[pkg] = source;
        reached[source]++;
      }
    }
    return reached;
  }

  private static int[] foreignPackagesReaching(StructuralGraph graph, int[] externalFanIn) {
    final int typeCount = graph.typeCount();
    final int[] start = new int[typeCount + 1];
    for (int edge = 0; edge < graph.edgeCount(); edge++) {
      start[graph.edgeTarget(edge) + 1]++;
    }
    for (int type = 0; type < typeCount; type++) {
      start[type + 1] += start[type];
    }
    final int[] sourcesByTarget = new int[graph.edgeCount()];
    final int[] next = Arrays.copyOf(start, typeCount);
    for (int edge = 0; edge < graph.edgeCount(); edge++) {
      sourcesByTarget[next[graph.edgeTarget(edge)]++] = graph.edgeSource(edge);
    }
    final int[] reaching = new int[typeCount];
    final int[] lastTarget = new int[graph.packageCount()];
    Arrays.fill(lastTarget, NONE);
    for (int target = 0; target < typeCount; target++) {
      for (int position = start[target]; position < start[target + 1]; position++) {
        final int pkg = graph.packageOf(sourcesByTarget[position]);
        if (pkg == graph.packageOf(target)) {
          continue;
        }
        externalFanIn[target]++;
        if (lastTarget[pkg] != target) {
          lastTarget[pkg] = target;
          reaching[target]++;
        }
      }
    }
    return reaching;
  }

  private static long pairKey(int source, int target) {
    return (long) source << Integer.SIZE | target;
  }

  private static int sourceOf(long key) {
    return (int) (key >>> Integer.SIZE);
  }

  private static int targetOf(long key) {
    return (int) key;
  }

  private static void requireNotCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException("projeção de pacotes cancelada");
    }
  }

  private static final class PairSum {

    private final int[] occurrences = new int[StructuralGraph.OCCURRENCE_SLOTS];
    private int typePairCount;
    private ResolutionQuality resolution = ResolutionQuality.RESOLVED;

    private void add(StructuralGraph graph, int edge) {
      typePairCount++;
      for (final DependencyType type : DEPENDENCY_TYPES) {
        occurrences[type.ordinal()] =
            Math.addExact(occurrences[type.ordinal()], graph.edgeOccurrence(edge, type));
      }
      resolution = ResolutionQuality.weakest(resolution, graph.edgeResolution(edge));
    }

    private PackageEdge toEdge(CodeElementId source, CodeElementId target) {
      final Map<DependencyType, Integer> present = new EnumMap<>(DependencyType.class);
      int total = 0;
      for (final DependencyType type : DEPENDENCY_TYPES) {
        final int count = occurrences[type.ordinal()];
        if (count > 0) {
          present.put(type, count);
          total = Math.addExact(total, count);
        }
      }
      return new PackageEdge(source, target, typePairCount, present, total, resolution);
    }
  }
}
