package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class PackageEdgeDao {

  private PackageEdgeDao() {}

  public static void insert(Connection connection, long analysisPk, List<PackageEdgeRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, () -> false);
  }

  public static void insert(
      Connection connection, long analysisPk, List<PackageEdgeRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public static List<PackageEdgeRow> list(Connection connection, long analysisPk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
