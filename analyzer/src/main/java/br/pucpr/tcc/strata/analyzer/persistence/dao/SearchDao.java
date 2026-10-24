package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public final class SearchDao {

  private SearchDao() {}

  public static List<SearchRow> search(
      Connection connection, long analysisPk, String folded, Set<ElementKind> kinds, int limit)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: o SQL da busca");
  }
}
