package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SliceDao {

  private static final String PACKAGE_NODES =
      """
      SELECT e.id, e.element_id, e.qualified_name, cm.loc, gm.fan_in, gm.fan_out, gm.pagerank,
             gm.resolved_occurrences, gm.inferred_occurrences,
             gm.unresolved_external_occurrences,
             (SELECT count(*) FROM code_element t
               WHERE t.analysis_pk = gm.analysis_pk AND t.parent_pk = e.id
                 AND t.kind = 'TYPE') AS type_count
        FROM graph_metric gm
        JOIN code_element e ON e.id = gm.element_pk
        JOIN code_metric cm ON cm.element_pk = gm.element_pk
       WHERE gm.analysis_pk = ? AND gm.granularity = 'PACKAGE'""";

  private static final String TYPE_NODES =
      """
      SELECT e.id, e.element_id, e.qualified_name, cm.loc, gm.fan_in, gm.fan_out, gm.pagerank,
             gm.resolved_occurrences, gm.inferred_occurrences,
             gm.unresolved_external_occurrences
        FROM code_element e
        JOIN graph_metric gm ON gm.element_pk = e.id
        JOIN code_metric cm ON cm.element_pk = e.id
       WHERE e.analysis_pk = ? AND e.parent_pk = ? AND e.kind = 'TYPE' AND e.is_external = 0
         AND gm.granularity = 'TYPE'""";

  private static final String INTRA_PACKAGE_DEPENDENCIES =
      """
      SELECT s.element_id AS source_id, t.element_id AS target_id, d.type, d.resolution,
             d.occurrences
        FROM code_element s
        JOIN dependency d ON d.source_pk = s.id
        JOIN code_element t ON t.id = d.target_pk
       WHERE s.analysis_pk = ?1 AND s.parent_pk = ?2 AND s.kind = 'TYPE'
         AND t.parent_pk = ?2 AND t.kind = 'TYPE'
         AND d.target_pk <> d.source_pk""";

  private SliceDao() {}

  public static List<SlicePackageRow> packageNodes(Connection connection, long analysisPk)
      throws SQLException {
    final List<SlicePackageRow> packages = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(PACKAGE_NODES)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          packages.add(
              new SlicePackageRow(
                  rows.getLong("id"),
                  new CodeElementId(rows.getString("element_id")),
                  rows.getString("qualified_name"),
                  rows.getLong("loc"),
                  rows.getInt("type_count"),
                  rows.getInt("fan_in"),
                  rows.getInt("fan_out"),
                  rows.getDouble("pagerank"),
                  rows.getLong("resolved_occurrences"),
                  rows.getLong("inferred_occurrences"),
                  rows.getLong("unresolved_external_occurrences")));
        }
      }
    }
    return List.copyOf(packages);
  }

  public static List<SliceTypeRow> typeNodes(Connection connection, long analysisPk, long packagePk)
      throws SQLException {
    final List<SliceTypeRow> types = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(TYPE_NODES)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, packagePk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          types.add(
              new SliceTypeRow(
                  rows.getLong("id"),
                  new CodeElementId(rows.getString("element_id")),
                  rows.getString("qualified_name"),
                  rows.getLong("loc"),
                  rows.getInt("fan_in"),
                  rows.getInt("fan_out"),
                  rows.getDouble("pagerank"),
                  rows.getLong("resolved_occurrences"),
                  rows.getLong("inferred_occurrences"),
                  rows.getLong("unresolved_external_occurrences")));
        }
      }
    }
    return List.copyOf(types);
  }

  public static List<Dependency> intraPackageDependencies(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    final List<Dependency> dependencies = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(INTRA_PACKAGE_DEPENDENCIES)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, packagePk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          dependencies.add(
              new Dependency(
                  new CodeElementId(rows.getString("source_id")),
                  new CodeElementId(rows.getString("target_id")),
                  DependencyType.valueOf(rows.getString("type")),
                  ResolutionQuality.valueOf(rows.getString("resolution")),
                  rows.getInt("occurrences")));
        }
      }
    }
    return List.copyOf(dependencies);
  }
}
