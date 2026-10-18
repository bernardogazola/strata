package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.Connection;
import java.sql.SQLException;

public final class SqlTransactions {

  private SqlTransactions() {}

  public static <T> T run(Connection connection, SqlWork<T> work) throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new IllegalStateException("já há uma transação aberta nesta conexão");
    }
    connection.setAutoCommit(false);
    final T result;
    try {
      result = work.run(connection);
      connection.commit();
    } catch (Throwable failure) {
      rollbackQuietly(connection, failure);
      restoreAutoCommit(connection, failure);
      throw failure;
    }
    connection.setAutoCommit(true);
    return result;
  }

  private static void rollbackQuietly(Connection connection, Throwable original) {
    try {
      connection.rollback();
    } catch (SQLException cleanup) {
      original.addSuppressed(cleanup);
    }
  }

  private static void restoreAutoCommit(Connection connection, Throwable original) {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException cleanup) {
      original.addSuppressed(cleanup);
    }
  }
}
