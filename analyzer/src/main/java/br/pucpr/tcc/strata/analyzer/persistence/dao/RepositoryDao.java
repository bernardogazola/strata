package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class RepositoryDao {

  private RepositoryDao() {}

  public static Optional<RepositoryRecord> find(Connection connection) throws SQLException {
    throw new UnsupportedOperationException("RepositoryDao em implementação");
  }

  public static void upsert(Connection connection, RepositoryRecord repository)
      throws SQLException {
    throw new UnsupportedOperationException("RepositoryDao em implementação");
  }
}
