package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.SQLException;

public final class GraphSliceQuery {

  private GraphSliceQuery() {}

  public static void requireParent(GraphLevel level, CodeElementId parent) {
    throw new UnsupportedOperationException("ainda não implementado: levels");
  }

  public static Slice slice(
      Connection connection,
      long analysisPk,
      GraphLevel level,
      CodeElementId parent,
      SliceBudget budget)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: graph.slice");
  }
}
