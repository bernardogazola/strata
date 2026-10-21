package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class ModuleDependencyDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO module_dependency (id, analysis_pk, file_pk, module_name, required_name,
                                     is_transitive, is_static, line)
      VALUES""";

  private static final int COLUMNS = 8;

  private static final String NEXT_ID = "SELECT coalesce(max(id), 0) + 1 FROM module_dependency";

  private static final String LIST =
      """
      SELECT id, file_pk, module_name, required_name, is_transitive, is_static, line
        FROM module_dependency
       WHERE analysis_pk = ?
       ORDER BY id""";

  private ModuleDependencyDao() {}

  public static long nextId(Connection connection) throws SQLException {
    return MultiRowInsert.nextId(connection, NEXT_ID);
  }

  public static void insert(Connection connection, long analysisPk, List<ModuleDependencyRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection,
      long analysisPk,
      List<ModuleDependencyRow> rows,
      BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.pk());
          statement.setLong(first + 1, analysisPk);
          statement.setLong(first + 2, row.filePk());
          statement.setString(first + 3, row.moduleName());
          statement.setString(first + 4, row.requiredName());
          statement.setInt(first + 5, row.transitive() ? 1 : 0);
          statement.setInt(first + 6, row.isStatic() ? 1 : 0);
          statement.setObject(first + 7, row.line());
        },
        cancelled);
  }

  public static List<ModuleDependencyRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<ModuleDependencyRow> modules = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          modules.add(
              new ModuleDependencyRow(
                  rows.getLong("id"),
                  rows.getLong("file_pk"),
                  rows.getString("module_name"),
                  rows.getString("required_name"),
                  rows.getInt("is_transitive") == 1,
                  rows.getInt("is_static") == 1,
                  nullableInt(rows, "line")));
        }
      }
    }
    return List.copyOf(modules);
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
