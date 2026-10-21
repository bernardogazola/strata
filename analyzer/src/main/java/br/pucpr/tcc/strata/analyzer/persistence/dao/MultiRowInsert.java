package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class MultiRowInsert {

  static final BooleanSupplier NEVER_CANCELLED = () -> false;

  private MultiRowInsert() {}

  static long nextId(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new SQLException("max(id) não devolveu linha");
      }
      return rows.getLong(1);
    }
  }

  static <R> void insertRows(
      Connection connection, String insertHead, int columns, List<R> rows, RowBinder<R> binder)
      throws SQLException {
    insertRows(connection, insertHead, columns, rows, binder, NEVER_CANCELLED);
  }

  static <R> void insertRows(
      Connection connection,
      String insertHead,
      int columns,
      List<R> rows,
      RowBinder<R> binder,
      BooleanSupplier cancelled)
      throws SQLException {
    final int perStatement = AnalysisConstants.ROWS_PER_INSERT;
    final int full = rows.size() / perStatement;
    if (full > 0) {
      try (PreparedStatement statement =
          connection.prepareStatement(valuesSql(insertHead, columns, perStatement))) {
        for (int chunk = 0; chunk < full; chunk++) {
          checkCancelled(cancelled);
          bindAll(
              statement,
              columns,
              rows.subList(chunk * perStatement, (chunk + 1) * perStatement),
              binder);
          statement.executeUpdate();
        }
      }
    }
    final List<R> tail = rows.subList(full * perStatement, rows.size());
    if (!tail.isEmpty()) {
      checkCancelled(cancelled);
      try (PreparedStatement statement =
          connection.prepareStatement(valuesSql(insertHead, columns, tail.size()))) {
        bindAll(statement, columns, tail, binder);
        statement.executeUpdate();
      }
    }
  }

  @FunctionalInterface
  interface RowBinder<R> {

    void bind(PreparedStatement statement, int first, R row) throws SQLException;
  }

  private static void checkCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException("análise cancelada no meio de uma escrita");
    }
  }

  private static <R> void bindAll(
      PreparedStatement statement, int columns, List<R> rows, RowBinder<R> binder)
      throws SQLException {
    int first = 1;
    for (final R row : rows) {
      binder.bind(statement, first, row);
      first += columns;
    }
  }

  private static String valuesSql(String insertHead, int columns, int rows) {
    final String row = "(" + String.join(", ", Collections.nCopies(columns, "?")) + ")";
    return insertHead + " " + String.join(", ", Collections.nCopies(rows, row));
  }
}
