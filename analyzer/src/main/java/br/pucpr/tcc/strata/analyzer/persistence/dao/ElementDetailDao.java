package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ElementDetailDao {

  private static final String ELEMENT =
      """
      SELECT e.id, e.element_id, e.type_kind, e.simple_name, e.qualified_name, e.modifiers,
             e.is_external, e.is_test, p.element_id AS package_id,
             p.qualified_name AS package_name, f.relative_path, e.start_line, e.end_line
        FROM code_element e
        LEFT JOIN code_element p ON p.id = e.parent_pk AND e.kind = 'TYPE'
        LEFT JOIN source_file f ON f.id = e.file_pk
       WHERE e.analysis_pk = ? AND e.element_id = ?""";

  private static final String CODE_METRIC =
      """
      SELECT element_pk, loc, unit_count, cc_sum, cc_max, cc_p90
        FROM code_metric
       WHERE element_pk = ?2 AND analysis_pk = ?1""";

  private static final String GRAPH_METRIC =
      """
      SELECT element_pk, granularity, fan_in, fan_out, fan_in_resolved, fan_out_resolved,
             fan_out_external, external_fan_in, pagerank, pagerank_out, scc_id, scc_size,
             bridge_span, component_id, internal_cohesion, betweenness, resolved_occurrences,
             inferred_occurrences, unresolved_external_occurrences
        FROM graph_metric
       WHERE element_pk = ?2 AND analysis_pk = ?1""";

  private static final String METHODS =
      """
      SELECT m.element_id, cm.cc_sum, m.start_line, m.end_line
        FROM code_element m
        LEFT JOIN code_metric cm ON cm.element_pk = m.id
       WHERE m.analysis_pk = ? AND m.parent_pk = ? AND m.kind = 'METHOD'
       ORDER BY m.start_line IS NULL, m.start_line, m.element_id
       LIMIT ?""";

  private static final String METHOD_COUNT =
      """
      SELECT count(*)
        FROM code_element
       WHERE analysis_pk = ? AND parent_pk = ? AND kind = 'METHOD'""";

  private ElementDetailDao() {}

  public static Optional<ElementDetailRow> element(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ELEMENT)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, elementId.value());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        final String typeKind = rows.getString("type_kind");
        final String packageId = rows.getString("package_id");
        return Optional.of(
            new ElementDetailRow(
                rows.getLong("id"),
                new CodeElementId(rows.getString("element_id")),
                typeKind == null ? null : TypeKind.valueOf(typeKind),
                rows.getString("simple_name"),
                rows.getString("qualified_name"),
                rows.getString("modifiers"),
                rows.getInt("is_external") == 1,
                rows.getInt("is_test") == 1,
                packageId == null ? null : new CodeElementId(packageId),
                rows.getString("package_name"),
                rows.getString("relative_path"),
                nullableInt(rows, "start_line"),
                nullableInt(rows, "end_line")));
      }
    }
  }

  public static Optional<CodeMetricRow> codeMetric(
      Connection connection, long analysisPk, long elementPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CODE_METRIC)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, elementPk);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(CodeMetricDao.read(rows)) : Optional.empty();
      }
    }
  }

  public static Optional<GraphMetricRow> graphMetric(
      Connection connection, long analysisPk, long elementPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(GRAPH_METRIC)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, elementPk);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(GraphMetricDao.read(rows)) : Optional.empty();
      }
    }
  }

  public static List<ElementMethodRow> methods(
      Connection connection, long analysisPk, long typePk, int limit) throws SQLException {
    final List<ElementMethodRow> methods = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(METHODS)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, typePk);
      statement.setInt(3, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          methods.add(
              new ElementMethodRow(
                  new CodeElementId(rows.getString("element_id")),
                  nullableInt(rows, "cc_sum"),
                  nullableInt(rows, "start_line"),
                  nullableInt(rows, "end_line")));
        }
      }
    }
    return List.copyOf(methods);
  }

  public static int methodCount(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(METHOD_COUNT)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, typePk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
