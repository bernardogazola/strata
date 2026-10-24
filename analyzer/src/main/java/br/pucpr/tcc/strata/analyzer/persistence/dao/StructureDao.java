package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class StructureDao {

  private StructureDao() {}

  public static boolean isServable(Connection connection, long analysisPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a proteção de retenção");
  }

  public static Optional<StructureElementRow> findElement(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: o pai");
  }

  public static Optional<PackageNameBounds> packageNameBounds(
      Connection connection, long analysisPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static boolean packageExists(Connection connection, long analysisPk, String qualifiedName)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }
}
