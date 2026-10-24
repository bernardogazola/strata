package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class DependencyPageDao {

  private DependencyPageDao() {}

  public static List<TypeDependencyRow> inbound(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: as linhas IN");
  }

  public static List<TypeDependencyRow> outbound(
      Connection connection, long analysisPk, long typePk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: as linhas OUT");
  }

  public static List<PackageDependencyRow> packageInbound(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: as linhas IN de pacote");
  }

  public static List<PackageDependencyRow> packageOutbound(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: as linhas OUT de pacote");
  }

  public static DependencyTotalsRow typeTotals(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: os totais de tipo");
  }

  public static DependencyTotalsRow packageTotals(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: os totais de pacote");
  }
}
