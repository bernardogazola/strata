package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Objects;

public record PairRow(Dependency dependency, boolean ambiguous) {

  public PairRow {
    Objects.requireNonNull(dependency, "dependency");
    if (ambiguous && dependency.resolution() != ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException(
          "uma linha ambígua é UNRESOLVED_EXTERNAL: "
              + dependency.source()
              + " -> "
              + dependency.target());
    }
  }

  public static PairRow unambiguous(Dependency dependency) {
    return new PairRow(dependency, false);
  }
}
