package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.Collection;
import java.util.List;

public final class DependencyAggregator {

  private DependencyAggregator() {}

  public static int weightOf(DependencyType type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static int rowWeight(Dependency row) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static List<DependencyEdge> aggregate(Collection<Dependency> rows) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
