package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class GraphMetricDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO graph_metric (element_pk, analysis_pk, granularity, fan_in, fan_out,
                                fan_in_resolved, fan_out_resolved, fan_out_external,
                                external_fan_in, pagerank, pagerank_out, scc_id, scc_size,
                                bridge_span, component_id, internal_cohesion, betweenness,
                                is_entry_point, entry_point_kind)
      VALUES""";

  private static final int COLUMNS = 19;

  private static final String LIST =
      """
      SELECT element_pk, granularity, fan_in, fan_out, fan_in_resolved, fan_out_resolved,
             fan_out_external, external_fan_in, pagerank, pagerank_out, scc_id, scc_size,
             bridge_span, component_id, internal_cohesion, betweenness
        FROM graph_metric
       WHERE analysis_pk = ?
       ORDER BY element_pk""";

  private GraphMetricDao() {}

  public static void insert(Connection connection, long analysisPk, List<GraphMetricRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection, long analysisPk, List<GraphMetricRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.elementPk());
          statement.setLong(first + 1, analysisPk);
          statement.setString(first + 2, row.granularity().name());
          statement.setInt(first + 3, row.fanIn());
          statement.setInt(first + 4, row.fanOut());
          statement.setObject(first + 5, row.fanInResolved());
          statement.setObject(first + 6, row.fanOutResolved());
          statement.setObject(first + 7, row.fanOutExternal());
          statement.setObject(first + 8, row.externalFanIn());
          statement.setDouble(first + 9, row.pagerank());
          statement.setDouble(first + 10, row.pagerankOut());
          statement.setObject(first + 11, row.sccId());
          statement.setObject(first + 12, row.sccSize());
          statement.setObject(first + 13, row.bridgeSpan());
          statement.setObject(first + 14, row.componentId());
          statement.setObject(first + 15, row.internalCohesion());
          bindNullableDouble(statement, first + 16, row.betweenness());
          statement.setNull(first + 17, Types.INTEGER);
          statement.setNull(first + 18, Types.VARCHAR);
        },
        cancelled);
  }

  public static List<GraphMetricRow> list(Connection connection, long analysisPk)
      throws SQLException {
    final List<GraphMetricRow> metrics = new ArrayList<>();
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

  private static GraphMetricRow read(ResultSet rows) throws SQLException {
    return new GraphMetricRow(
        rows.getLong("element_pk"),
        ElementKind.valueOf(rows.getString("granularity")),
        rows.getInt("fan_in"),
        rows.getInt("fan_out"),
        nullableInt(rows, "fan_in_resolved"),
        nullableInt(rows, "fan_out_resolved"),
        nullableInt(rows, "fan_out_external"),
        nullableInt(rows, "external_fan_in"),
        rows.getDouble("pagerank"),
        rows.getDouble("pagerank_out"),
        nullableInt(rows, "scc_id"),
        nullableInt(rows, "scc_size"),
        nullableInt(rows, "bridge_span"),
        nullableInt(rows, "component_id"),
        nullableInt(rows, "internal_cohesion"),
        nullableDouble(rows, "betweenness"));
  }

  private static void bindNullableDouble(PreparedStatement statement, int index, Double value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.REAL);
    } else {
      statement.setDouble(index, value);
    }
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }

  private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
    final double value = rows.getDouble(column);
    return rows.wasNull() ? null : value;
  }
}
