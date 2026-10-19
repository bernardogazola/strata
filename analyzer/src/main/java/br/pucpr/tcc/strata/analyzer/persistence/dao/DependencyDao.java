package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class DependencyDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO dependency (id, analysis_pk, source_pk, target_pk, type, resolution,
                              occurrences, weight, ambiguous)
      VALUES""";

  private static final int COLUMNS = 9;

  private static final String NEXT_ID = "SELECT coalesce(max(id), 0) + 1 FROM dependency";

  private static final String LIST =
      """
      SELECT id, source_pk, target_pk, type, resolution, occurrences, weight, ambiguous
        FROM dependency
       WHERE analysis_pk = ?
       ORDER BY id""";

  private DependencyDao() {}

  public static long nextId(Connection connection) throws SQLException {
    return MultiRowInsert.nextId(connection, NEXT_ID);
  }

  public static void insert(Connection connection, long analysisPk, List<DependencyRow> rows)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.pk());
          statement.setLong(first + 1, analysisPk);
          statement.setLong(first + 2, row.sourcePk());
          statement.setLong(first + 3, row.targetPk());
          statement.setString(first + 4, row.type().name());
          statement.setString(first + 5, row.resolution().name());
          statement.setInt(first + 6, row.occurrences());
          statement.setInt(first + 7, row.weight());
          statement.setInt(first + 8, row.ambiguous() ? 1 : 0);
        });
  }

  public static List<DependencyRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<DependencyRow> dependencies = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          dependencies.add(
              new DependencyRow(
                  rows.getLong("id"),
                  rows.getLong("source_pk"),
                  rows.getLong("target_pk"),
                  DependencyType.valueOf(rows.getString("type")),
                  ResolutionQuality.valueOf(rows.getString("resolution")),
                  rows.getInt("occurrences"),
                  rows.getInt("weight"),
                  rows.getInt("ambiguous") == 1));
        }
      }
    }
    return List.copyOf(dependencies);
  }
}
