package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record GraphMetricRow(
    long elementPk,
    ElementKind granularity,
    int fanIn,
    int fanOut,
    Integer fanInResolved,
    Integer fanOutResolved,
    Integer fanOutExternal,
    Integer externalFanIn,
    double pagerank,
    double pagerankOut,
    Integer sccId,
    Integer sccSize,
    Integer bridgeSpan,
    Integer componentId,
    Integer internalCohesion,
    Double betweenness) {

  public GraphMetricRow {
    Objects.requireNonNull(granularity, "granularity");
    final boolean type = granularity == ElementKind.TYPE;
    if (!type && granularity != ElementKind.PACKAGE) {
      throw new IllegalArgumentException("um nó do grafo é TYPE ou PACKAGE: " + elementPk);
    }
    if (fanIn < 0 || fanOut < 0) {
      throw new IllegalArgumentException("um fan negativo na linha do grafo de " + elementPk);
    }
    if (!isRank(pagerank) || !isRank(pagerankOut)) {
      throw new IllegalArgumentException("um pagerank fora de (0, 1]: " + elementPk);
    }
    if ((sccId == null) != (sccSize == null) || (sccId != null && (sccId < 1 || sccSize < 2))) {
      throw new IllegalArgumentException("scc_id e scc_size vêm juntos: " + elementPk);
    }
    final boolean typeColumns =
        fanInResolved != null
            && fanOutResolved != null
            && fanOutExternal != null
            && externalFanIn != null
            && bridgeSpan != null;
    final boolean noTypeColumn =
        fanInResolved == null
            && fanOutResolved == null
            && fanOutExternal == null
            && externalFanIn == null
            && bridgeSpan == null;
    final boolean packageColumns = componentId != null && internalCohesion != null;
    final boolean noPackageColumn =
        componentId == null && internalCohesion == null && betweenness == null;
    if (type ? !(typeColumns && noPackageColumn) : !(noTypeColumn && packageColumns)) {
      throw new IllegalArgumentException("colunas da granularidade errada: " + elementPk);
    }
    if (type
        && (fanInResolved < 0
            || fanInResolved > fanIn
            || fanOutResolved < 0
            || fanOutResolved > fanOut
            || fanOutExternal < 0
            || externalFanIn < 0
            || externalFanIn > fanIn
            || bridgeSpan < 0)) {
      throw new IllegalArgumentException("contagens de tipo inconsistentes: " + elementPk);
    }
    if (!type
        && (componentId < 1
            || internalCohesion < 0
            || (betweenness != null && (!Double.isFinite(betweenness) || betweenness < 0)))) {
      throw new IllegalArgumentException("valores de pacote inconsistentes: " + elementPk);
    }
  }

  private static boolean isRank(double value) {
    return Double.isFinite(value) && value > 0d && value <= 1d;
  }
}
