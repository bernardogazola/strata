package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class SliceDao {

  private SliceDao() {}

  public static List<SlicePackageRow> packageNodes(Connection connection, long analysisPk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: nós de pacote");
  }

  public static List<SliceTypeRow> typeNodes(Connection connection, long analysisPk, long packagePk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: nós de tipo");
  }

  public static List<Dependency> intraPackageDependencies(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: arestas de tipo");
  }
}
