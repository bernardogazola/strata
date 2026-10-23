package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import java.util.function.BooleanSupplier;

public final class StructuralGraphAnalysis {

  private StructuralGraphAnalysis() {}

  public static StructuralGraphResult compute(
      GraphInput input, ExternalPackages packages, BooleanSupplier cancelled) {
    return compute(
        input,
        packages,
        cancelled,
        AnalysisConstants.PAGERANK_MAX_ITER,
        AnalysisConstants.BETWEENNESS_MAX_NODES);
  }

  static StructuralGraphResult compute(
      GraphInput input,
      ExternalPackages packages,
      BooleanSupplier cancelled,
      int pageRankMaxIterations,
      int betweennessMaxNodes) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
