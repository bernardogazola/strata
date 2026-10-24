package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class OutlineDao {

  private OutlineDao() {}

  public static List<OutlineRow> rootPage(
      Connection connection, long analysisPk, Long afterPk, int fetch) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a página da raiz");
  }

  public static List<OutlineRow> typePage(
      Connection connection, long analysisPk, long packagePk, Long afterPk, int fetch)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: uma página de pacote");
  }

  public static boolean isListed(
      Connection connection, long analysisPk, long elementPk, Long parentPk, ElementKind kind)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a linha do cursor");
  }
}
