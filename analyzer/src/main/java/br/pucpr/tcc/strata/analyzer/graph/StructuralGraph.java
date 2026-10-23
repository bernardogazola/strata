package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class StructuralGraph {

  private static final DependencyType[] DEPENDENCY_TYPES = DependencyType.values();

  static final int OCCURRENCE_SLOTS = DEPENDENCY_TYPES.length;

  private final CodeElementId[] typeIds;
  private final int[] packageOfType;
  private final CodeElementId[] packageIds;
  private final int[] edgeSources;
  private final int[] edgeTargets;
  private final ResolutionQuality[] edgeResolutions;
  private final int[] edgeOccurrences;
  private final int[] fanOutExternal;
  private final long selfPairs;
  private final long externalPairs;
  private final long denylistedPairs;
  private final Graph<Integer, DefaultEdge> forward;

  StructuralGraph(
      CodeElementId[] typeIds,
      int[] packageOfType,
      CodeElementId[] packageIds,
      TypeEdges edges,
      int[] fanOutExternal,
      long selfPairs,
      long denylistedPairs) {
    this.typeIds = typeIds;
    this.packageOfType = packageOfType;
    this.packageIds = packageIds;
    this.edgeSources = edges.sources();
    this.edgeTargets = edges.targets();
    this.edgeResolutions = edges.resolutions();
    this.edgeOccurrences = edges.occurrences();
    this.fanOutExternal = fanOutExternal;
    this.selfPairs = selfPairs;
    long external = 0;
    for (final int count : fanOutExternal) {
      external += count;
    }
    this.externalPairs = external;
    this.denylistedPairs = denylistedPairs;
    this.forward = DenseGraphs.forward(typeIds.length, edgeSources, edgeTargets);
  }

  public int typeCount() {
    return typeIds.length;
  }

  public CodeElementId typeId(int type) {
    return typeIds[type];
  }

  public int packageCount() {
    return packageIds.length;
  }

  public CodeElementId packageId(int pkg) {
    return packageIds[pkg];
  }

  public int packageOf(int type) {
    return packageOfType[type];
  }

  public int edgeCount() {
    return edgeSources.length;
  }

  public int edgeSource(int edge) {
    return edgeSources[edge];
  }

  public int edgeTarget(int edge) {
    return edgeTargets[edge];
  }

  public ResolutionQuality edgeResolution(int edge) {
    return edgeResolutions[edge];
  }

  public Map<DependencyType, Integer> edgeOccurrences(int edge) {
    final Map<DependencyType, Integer> occurrences = new EnumMap<>(DependencyType.class);
    for (final DependencyType type : DEPENDENCY_TYPES) {
      final int count = edgeOccurrence(edge, type);
      if (count > 0) {
        occurrences.put(type, count);
      }
    }
    return Collections.unmodifiableMap(occurrences);
  }

  int edgeOccurrence(int edge, DependencyType type) {
    Objects.checkIndex(edge, edgeSources.length);
    return edgeOccurrences[edge * OCCURRENCE_SLOTS + type.ordinal()];
  }

  public int fanOutExternal(int type) {
    return fanOutExternal[type];
  }

  public long selfPairs() {
    return selfPairs;
  }

  public long externalPairs() {
    return externalPairs;
  }

  public long denylistedPairs() {
    return denylistedPairs;
  }

  public Graph<Integer, DefaultEdge> forward() {
    return forward;
  }
}
