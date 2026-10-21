package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class CodeMetricDao {

  private CodeMetricDao() {}

  public static void insert(Connection connection, long analysisPk, List<CodeMetricRow> rows)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static int rollUpPackages(Connection connection, long analysisPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static List<CodeMetricRow> list(Connection connection, long analysisPk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
