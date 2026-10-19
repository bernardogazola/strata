package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SourceRootDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO source_root (id, analysis_pk, source_root_id, relative_path, kind,
                               java_file_count)
      VALUES""";

  private static final int COLUMNS = 6;

  private static final String NEXT_ID = "SELECT coalesce(max(id), 0) + 1 FROM source_root";

  private static final String LIST =
      """
      SELECT id, source_root_id, relative_path, kind, java_file_count
        FROM source_root
       WHERE analysis_pk = ?
       ORDER BY id""";

  private SourceRootDao() {}

  public static long nextId(Connection connection) throws SQLException {
    return MultiRowInsert.nextId(connection, NEXT_ID);
  }

  public static void insert(Connection connection, long analysisPk, List<SourceRootRow> rows)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.pk());
          statement.setLong(first + 1, analysisPk);
          statement.setString(first + 2, row.sourceRootId().value());
          statement.setString(first + 3, row.relativePath());
          statement.setString(first + 4, row.kind().name());
          statement.setInt(first + 5, row.javaFileCount());
        });
  }

  public static List<SourceRootRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<SourceRootRow> roots = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          roots.add(
              new SourceRootRow(
                  rows.getLong("id"),
                  new SourceRootId(rows.getString("source_root_id")),
                  rows.getString("relative_path"),
                  SourceRootKind.valueOf(rows.getString("kind")),
                  rows.getInt("java_file_count")));
        }
      }
    }
    return List.copyOf(roots);
  }
}
