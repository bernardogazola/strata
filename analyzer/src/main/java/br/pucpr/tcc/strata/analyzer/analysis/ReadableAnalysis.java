package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import java.util.Objects;

public record ReadableAnalysis(
    AnalysisId analysisId, long analysisPk, SqliteConnections connections) {

  public ReadableAnalysis {
    Objects.requireNonNull(analysisId, "analysisId");
    Objects.requireNonNull(connections, "connections");
    if (analysisPk < 1) {
      throw new IllegalArgumentException("analysisPk deve ser >= 1: " + analysisPk);
    }
  }
}
