package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Arrays;
import java.util.Map;

final class TypeEdges {

  private static final int INITIAL_CAPACITY = 1_024;

  private int count;
  private int[] sources = new int[INITIAL_CAPACITY];
  private int[] targets = new int[INITIAL_CAPACITY];
  private ResolutionQuality[] resolutions = new ResolutionQuality[INITIAL_CAPACITY];
  private int[] occurrences = new int[INITIAL_CAPACITY * StructuralGraph.OCCURRENCE_SLOTS];

  void add(
      int source, int target, ResolutionQuality resolution, Map<DependencyType, Integer> byType) {
    if (count > 0) {
      final int previousSource = sources[count - 1];
      final int previousTarget = targets[count - 1];
      if (source < previousSource || source == previousSource && target <= previousTarget) {
        throw new IllegalStateException(
            "arestas de tipo fora da ordem (source, target): "
                + previousSource
                + " -> "
                + previousTarget
                + " e depois "
                + source
                + " -> "
                + target);
      }
    }
    if (count == sources.length) {
      grow();
    }
    sources[count] = source;
    targets[count] = target;
    resolutions[count] = resolution;
    final int base = count * StructuralGraph.OCCURRENCE_SLOTS;
    byType.forEach((type, occurrence) -> occurrences[base + type.ordinal()] = occurrence);
    count++;
  }

  int[] sources() {
    return Arrays.copyOf(sources, count);
  }

  int[] targets() {
    return Arrays.copyOf(targets, count);
  }

  ResolutionQuality[] resolutions() {
    return Arrays.copyOf(resolutions, count);
  }

  int[] occurrences() {
    return Arrays.copyOf(occurrences, count * StructuralGraph.OCCURRENCE_SLOTS);
  }

  private void grow() {
    final int capacity = Math.multiplyExact(sources.length, 2);
    sources = Arrays.copyOf(sources, capacity);
    targets = Arrays.copyOf(targets, capacity);
    resolutions = Arrays.copyOf(resolutions, capacity);
    occurrences =
        Arrays.copyOf(occurrences, Math.multiplyExact(capacity, StructuralGraph.OCCURRENCE_SLOTS));
  }
}
