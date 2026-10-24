package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;

public record SliceBudget(int nodes, int edges) {

  public SliceBudget {
    if (nodes < 1 || edges < 1) {
      throw new IllegalArgumentException(
          "um orçamento de recorte é positivo: " + nodes + "/" + edges);
    }
  }

  public static SliceBudget of(GraphLevel level, Integer limit) {
    if (level == null) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LEVEL_REQUIRED);
    }
    final SliceBudget ceiling =
        switch (level) {
          case PACKAGE ->
              new SliceBudget(
                  AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_NODES,
                  AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES);
          case TYPE ->
              new SliceBudget(
                  AnalysisConstants.GRAPH_SLICE_TYPE_MAX_NODES,
                  AnalysisConstants.GRAPH_SLICE_TYPE_MAX_EDGES);
          case TYPE_NEIGHBORHOOD ->
              throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LEVEL_NOT_SERVED);
        };
    if (limit == null) {
      return ceiling;
    }
    if (limit < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return new SliceBudget(Math.min(limit, ceiling.nodes()), ceiling.edges());
  }
}
