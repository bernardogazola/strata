package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.SQLException;

public final class DependencyPageQuery {

  private DependencyPageQuery() {}

  public static int pageSize(Integer requested) {
    if (requested == null) {
      return AnalysisConstants.DEPENDENCY_PAGE_DEFAULT;
    }
    if (requested < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return Math.min(requested, AnalysisConstants.DEPENDENCY_PAGE_MAX);
  }

  public static void requireElement(CodeElementId elementId) {
    ElementDetailQuery.requireElement(elementId);
  }

  public static DependencyPage page(
      Connection connection,
      long analysisPk,
      CodeElementId elementId,
      DependencyDirection direction,
      DependencyCursor after,
      int limit)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a página de dependências");
  }
}
