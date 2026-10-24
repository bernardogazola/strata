package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.SQLException;

public final class OutlineQuery {

  private OutlineQuery() {}

  public static int pageSize(Integer requested) {
    throw new UnsupportedOperationException("ainda não implementado: o tamanho da página");
  }

  public static void requireParent(CodeElementId parent) {
    throw new UnsupportedOperationException("ainda não implementado: outline.children");
  }

  public static OutlinePage page(
      Connection connection, long analysisPk, CodeElementId parent, OutlineCursor after, int limit)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: outline.children");
  }
}
