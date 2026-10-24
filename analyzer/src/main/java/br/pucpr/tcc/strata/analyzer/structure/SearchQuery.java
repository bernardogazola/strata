package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public final class SearchQuery {

  private SearchQuery() {}

  public static Set<ElementKind> kinds(List<ElementKind> requested) {
    throw new UnsupportedOperationException("ainda não implementado: kinds");
  }

  public static int resultLimit(Integer requested) {
    throw new UnsupportedOperationException("ainda não implementado: o limite de resultados");
  }

  public static List<SearchHit> search(
      Connection connection, long analysisPk, SearchTerm term, Set<ElementKind> kinds, int limit)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: graph.search");
  }
}
