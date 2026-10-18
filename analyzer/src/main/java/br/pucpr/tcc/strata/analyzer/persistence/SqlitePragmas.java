package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.Connection;
import java.sql.SQLException;

final class SqlitePragmas {

  private SqlitePragmas() {}

  static void applyToWriter(Connection writer) throws SQLException {
    throw new UnsupportedOperationException("SqlitePragmas em implementação");
  }

  static void applyToReader(Connection reader) throws SQLException {
    throw new UnsupportedOperationException("SqlitePragmas em implementação");
  }
}
