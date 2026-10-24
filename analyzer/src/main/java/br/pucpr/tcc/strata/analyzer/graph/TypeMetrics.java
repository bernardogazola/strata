package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;
import java.util.Optional;

public record TypeMetrics(
    CodeElementId id,
    int fanIn,
    int fanOut,
    int fanInResolved,
    int fanOutResolved,
    int fanOutExternal,
    int externalFanIn,
    double pagerank,
    double pagerankOut,
    Cycle cycle,
    int bridgeSpan,
    ResolutionMix resolutionMix) {

  public TypeMetrics {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(resolutionMix, "resolutionMix");
    if (id.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException("as métricas de tipo pedem um id de tipo: " + id);
    }
    if (fanIn < 0
        || fanOut < 0
        || fanOutExternal < 0
        || bridgeSpan < 0
        || fanInResolved < 0
        || fanInResolved > fanIn
        || fanOutResolved < 0
        || fanOutResolved > fanOut
        || externalFanIn < 0
        || externalFanIn > fanIn) {
      throw new IllegalArgumentException("contagens inconsistentes para " + id);
    }
    if (!Ranks.isRank(pagerank) || !Ranks.isRank(pagerankOut)) {
      throw new IllegalArgumentException("um pagerank fora de (0, 1] para " + id);
    }
  }

  public Optional<Cycle> inCycle() {
    return Optional.ofNullable(cycle);
  }
}
