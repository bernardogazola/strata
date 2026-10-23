package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class PackageEdgeDao {

  private static final List<DependencyType> OCCURRENCE_TYPES =
      List.of(
          DependencyType.IMPORTS,
          DependencyType.EXTENDS,
          DependencyType.IMPLEMENTS,
          DependencyType.CALLS,
          DependencyType.FIELD_TYPE,
          DependencyType.PARAMETER_TYPE,
          DependencyType.RETURN_TYPE,
          DependencyType.ANNOTATION_REFERENCE);

  private static final String INSERT_HEAD =
      """
      INSERT INTO package_edge (source_pk, target_pk, analysis_pk, type_pair_count,
                                occurrence_total, resolution, imports, extends, implements, calls,
                                field_type, parameter_type, return_type, annotation_reference)
      VALUES""";

  private static final int COLUMNS = 14;

  private static final int FIRST_OCCURRENCE_COLUMN = 7;

  private static final String LIST =
      """
      SELECT source_pk, target_pk, type_pair_count, occurrence_total, resolution, imports, extends,
             implements, calls, field_type, parameter_type, return_type, annotation_reference
        FROM package_edge
       WHERE analysis_pk = ?
       ORDER BY source_pk, target_pk""";

  private static final int FIRST_LISTED_OCCURRENCE = 6;

  private PackageEdgeDao() {}

  public static void insert(Connection connection, long analysisPk, List<PackageEdgeRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection, long analysisPk, List<PackageEdgeRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.sourcePk());
          statement.setLong(first + 1, row.targetPk());
          statement.setLong(first + 2, analysisPk);
          statement.setInt(first + 3, row.typePairCount());
          statement.setInt(first + 4, row.occurrenceTotal());
          statement.setString(first + 5, row.resolution().name());
          final int occurrences = first + FIRST_OCCURRENCE_COLUMN - 1;
          for (int i = 0; i < OCCURRENCE_TYPES.size(); i++) {
            statement.setInt(
                occurrences + i, row.occurrences().getOrDefault(OCCURRENCE_TYPES.get(i), 0));
          }
        },
        cancelled);
  }

  public static List<PackageEdgeRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<PackageEdgeRow> edges = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          edges.add(read(rows));
        }
      }
    }
    return List.copyOf(edges);
  }

  private static PackageEdgeRow read(ResultSet rows) throws SQLException {
    return new PackageEdgeRow(
        rows.getLong("source_pk"),
        rows.getLong("target_pk"),
        rows.getInt("type_pair_count"),
        rows.getInt("occurrence_total"),
        ResolutionQuality.valueOf(rows.getString("resolution")),
        presentOccurrences(rows));
  }

  private static Map<DependencyType, Integer> presentOccurrences(ResultSet rows)
      throws SQLException {
    final Map<DependencyType, Integer> present = new EnumMap<>(DependencyType.class);
    for (int i = 0; i < OCCURRENCE_TYPES.size(); i++) {
      final int occurrences = rows.getInt(FIRST_LISTED_OCCURRENCE + i);
      if (occurrences > 0) {
        present.put(OCCURRENCE_TYPES.get(i), occurrences);
      }
    }
    return present;
  }
}
