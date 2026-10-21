package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class EvidenceDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO dependency_evidence (dependency_pk, from_method_pk, to_method_pk, file_pk, line,
                                       resolution)
      VALUES""";

  private static final int COLUMNS = 6;

  private static final String LIST =
      """
      SELECT e.dependency_pk, e.from_method_pk, e.to_method_pk, e.file_pk, e.line, e.resolution
        FROM dependency_evidence AS e
        JOIN dependency AS d ON d.id = e.dependency_pk
       WHERE d.analysis_pk = ?
       ORDER BY e.id""";

  private EvidenceDao() {}

  public static void insert(Connection connection, List<EvidenceRow> rows) throws SQLException {
    insert(connection, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection, List<EvidenceRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.dependencyPk());
          statement.setObject(first + 1, row.fromMethodPk());
          statement.setObject(first + 2, row.toMethodPk());
          statement.setObject(first + 3, row.filePk());
          statement.setObject(first + 4, row.line());
          statement.setString(first + 5, row.resolution().name());
        },
        cancelled);
  }

  public static List<EvidenceRow> list(Connection connection, long analysisPk) throws SQLException {
    final List<EvidenceRow> evidence = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          evidence.add(
              new EvidenceRow(
                  rows.getLong("dependency_pk"),
                  nullableLong(rows, "from_method_pk"),
                  nullableLong(rows, "to_method_pk"),
                  nullableLong(rows, "file_pk"),
                  nullableInt(rows, "line"),
                  ResolutionQuality.valueOf(rows.getString("resolution"))));
        }
      }
    }
    return List.copyOf(evidence);
  }

  private static Long nullableLong(ResultSet rows, String column) throws SQLException {
    final long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
