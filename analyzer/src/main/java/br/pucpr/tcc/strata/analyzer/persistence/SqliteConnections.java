package br.pucpr.tcc.strata.analyzer.persistence;

import java.nio.file.Path;
import java.sql.SQLException;

public final class SqliteConnections implements AutoCloseable {

  private SqliteConnections() {}

  public static SqliteConnections open(Path databaseFile) throws SQLException {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }

  public boolean created() {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }

  public Path databaseFile() {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }

  public <T> T write(SqlWork<T> work) throws SQLException {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }

  public <T> T read(SqlWork<T> work) throws SQLException {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }

  @Override
  public void close() throws SQLException {
    throw new UnsupportedOperationException("SqliteConnections em implementação");
  }
}
