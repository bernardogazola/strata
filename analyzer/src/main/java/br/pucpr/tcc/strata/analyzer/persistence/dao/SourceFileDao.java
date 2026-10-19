package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SourceFileDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO source_file (id, analysis_pk, relative_path, size_bytes, mtime_millis, status)
      VALUES""";

  private static final int COLUMNS = 6;

  private static final String NEXT_ID = "SELECT coalesce(max(id), 0) + 1 FROM source_file";

  private static final String UPDATE_STATUS = "UPDATE source_file SET status = ? WHERE id = ?";

  private static final String LIST =
      """
      SELECT id, relative_path, size_bytes, mtime_millis, status
        FROM source_file
       WHERE analysis_pk = ?
       ORDER BY id""";

  private SourceFileDao() {}

  public static long nextId(Connection connection) throws SQLException {
    return MultiRowInsert.nextId(connection, NEXT_ID);
  }

  public static void insert(Connection connection, long analysisPk, List<SourceFileRow> rows)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.pk());
          statement.setLong(first + 1, analysisPk);
          statement.setString(first + 2, row.relativePath());
          statement.setLong(first + 3, row.sizeBytes());
          statement.setLong(first + 4, row.mtimeMillis());
          statement.setString(first + 5, row.status().name());
        });
  }

  public static void updateStatus(Connection connection, long pk, SourceFileStatus status)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
      statement.setString(1, status.name());
      statement.setLong(2, pk);
      if (statement.executeUpdate() == 0) {
        throw new SQLException("nenhuma linha em source_file com pk " + pk);
      }
    }
  }

  public static List<SourceFileRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<SourceFileRow> files = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          files.add(
              new SourceFileRow(
                  rows.getLong("id"),
                  rows.getString("relative_path"),
                  rows.getLong("size_bytes"),
                  rows.getLong("mtime_millis"),
                  SourceFileStatus.valueOf(rows.getString("status"))));
        }
      }
    }
    return List.copyOf(files);
  }
}
