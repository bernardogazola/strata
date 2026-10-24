package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import java.util.Objects;

public record GraphFacts(
    int fanIn, int fanOut, double pagerank, Cycle cycle, ResolutionMix resolutionMix) {

  public GraphFacts {
    Objects.requireNonNull(resolutionMix, "resolutionMix");
    if (fanIn < 0 || fanOut < 0) {
      throw new IllegalArgumentException("um fan negativo: " + fanIn + "/" + fanOut);
    }
    if (!Double.isFinite(pagerank) || pagerank < 0d || pagerank > 1d) {
      throw new IllegalArgumentException("um pagerank fora de [0, 1]: " + pagerank);
    }
  }
}
