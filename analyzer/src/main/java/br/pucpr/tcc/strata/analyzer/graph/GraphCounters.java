package br.pucpr.tcc.strata.analyzer.graph;

import java.util.LinkedHashMap;
import java.util.Map;

public record GraphCounters(
    long typeNodes,
    long typeEdges,
    long selfPairs,
    long externalPairs,
    long denylistedPairs,
    long packageNodes,
    long packageEdges,
    long typeCycles,
    long typesInCycles,
    long packageCycles,
    long packagesInCycles,
    long pagerankUnconverged,
    long betweennessSkipped) {

  public static final int PAGERANK_VECTORS = 4;

  public static final int MAX_BETWEENNESS_SKIPPED = 1;

  public static final GraphCounters EMPTY =
      new GraphCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  public GraphCounters {
    final long[] values = {
      typeNodes,
      typeEdges,
      selfPairs,
      externalPairs,
      denylistedPairs,
      packageNodes,
      packageEdges,
      typeCycles,
      typesInCycles,
      packageCycles,
      packagesInCycles,
      pagerankUnconverged,
      betweennessSkipped
    };
    for (final long value : values) {
      if (value < 0) {
        throw new IllegalArgumentException("um contador é >= 0: " + value);
      }
    }
    if (pagerankUnconverged > PAGERANK_VECTORS || betweennessSkipped > MAX_BETWEENNESS_SKIPPED) {
      throw new IllegalArgumentException(
          "pagerankUnconverged <= "
              + PAGERANK_VECTORS
              + " e betweennessSkipped <= "
              + MAX_BETWEENNESS_SKIPPED);
    }
  }

  public Map<String, Long> asMap() {
    final Map<String, Long> counters = new LinkedHashMap<>();
    counters.put("typeNodes", typeNodes);
    counters.put("typeEdges", typeEdges);
    counters.put("selfPairs", selfPairs);
    counters.put("externalPairs", externalPairs);
    counters.put("denylistedPairs", denylistedPairs);
    counters.put("packageNodes", packageNodes);
    counters.put("packageEdges", packageEdges);
    counters.put("typeCycles", typeCycles);
    counters.put("typesInCycles", typesInCycles);
    counters.put("packageCycles", packageCycles);
    counters.put("packagesInCycles", packagesInCycles);
    counters.put("pagerankUnconverged", pagerankUnconverged);
    counters.put("betweennessSkipped", betweennessSkipped);
    return counters;
  }
}
