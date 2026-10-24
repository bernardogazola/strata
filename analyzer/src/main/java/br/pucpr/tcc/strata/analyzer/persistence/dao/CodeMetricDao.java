package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class CodeMetricDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO code_metric (element_pk, analysis_pk, loc, unit_count, cc_sum, cc_max, cc_p90)
      VALUES""";

  private static final int COLUMNS = 7;

  private static final String ROLL_UP_PACKAGES =
      """
      INSERT INTO code_metric (element_pk, analysis_pk, loc, unit_count, cc_sum, cc_max, cc_p90)
      SELECT package.id,
             package.analysis_pk,
             (SELECT coalesce(sum(file_metric.loc), 0)
                FROM code_element AS file
                JOIN code_metric AS file_metric ON file_metric.element_pk = file.id
               WHERE file.analysis_pk = package.analysis_pk
                 AND file.parent_pk = package.id
                 AND file.kind = ?),
             coalesce(sum(type_metric.unit_count), 0),
             coalesce(sum(type_metric.cc_sum), 0),
             coalesce(max(type_metric.cc_max), 0),
             NULL
        FROM code_element AS package
        LEFT JOIN code_element AS type
          ON type.analysis_pk = package.analysis_pk
         AND type.parent_pk = package.id
         AND type.kind = ?
         AND type.is_external = 0
        LEFT JOIN code_metric AS type_metric ON type_metric.element_pk = type.id
       WHERE package.analysis_pk = ? AND package.kind = ?
       GROUP BY package.id
       ORDER BY package.id""";

  private static final String LIST =
      """
      SELECT element_pk, loc, unit_count, cc_sum, cc_max, cc_p90
        FROM code_metric
       WHERE analysis_pk = ?
       ORDER BY element_pk""";

  private CodeMetricDao() {}

  public static void insert(Connection connection, long analysisPk, List<CodeMetricRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection, long analysisPk, List<CodeMetricRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.elementPk());
          statement.setLong(first + 1, analysisPk);
          statement.setInt(first + 2, row.loc());
          statement.setObject(first + 3, row.unitCount());
          statement.setObject(first + 4, row.ccSum());
          statement.setObject(first + 5, row.ccMax());
          statement.setObject(first + 6, row.ccP90());
        },
        cancelled);
  }

  public static int rollUpPackages(Connection connection, long analysisPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ROLL_UP_PACKAGES)) {
      statement.setString(1, ElementKind.FILE.name());
      statement.setString(2, ElementKind.TYPE.name());
      statement.setLong(3, analysisPk);
      statement.setString(4, ElementKind.PACKAGE.name());
      return statement.executeUpdate();
    }
  }

  public static List<CodeMetricRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<CodeMetricRow> metrics = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          metrics.add(read(rows));
        }
      }
    }
    return List.copyOf(metrics);
  }

  static CodeMetricRow read(ResultSet rows) throws SQLException {
    return new CodeMetricRow(
        rows.getLong("element_pk"),
        rows.getInt("loc"),
        nullableInt(rows, "unit_count"),
        nullableInt(rows, "cc_sum"),
        nullableInt(rows, "cc_max"),
        nullableInt(rows, "cc_p90"));
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
