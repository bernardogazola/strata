package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;
import java.util.OptionalDouble;

public record PackageMetrics(
    CodeElementId id,
    int fanIn,
    int fanOut,
    double pagerank,
    double pagerankOut,
    Cycle cycle,
    int componentId,
    int internalCohesion,
    Double betweenness) {

  public PackageMetrics {
    Objects.requireNonNull(id, "id");
    if (id.kind() != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("as métricas de pacote pedem um id de pacote: " + id);
    }
    if (fanIn < 0 || fanOut < 0 || internalCohesion < 0 || componentId < 1) {
      throw new IllegalArgumentException("contagens inconsistentes para " + id);
    }
    if (!Ranks.isRank(pagerank) || !Ranks.isRank(pagerankOut)) {
      throw new IllegalArgumentException("um pagerank fora de (0, 1] para " + id);
    }
    if (betweenness != null && (!Double.isFinite(betweenness) || betweenness < 0)) {
      throw new IllegalArgumentException("uma betweenness que não é um finito >= 0 para " + id);
    }
  }

  public OptionalDouble betweennessIfComputed() {
    return betweenness == null ? OptionalDouble.empty() : OptionalDouble.of(betweenness);
  }
}
