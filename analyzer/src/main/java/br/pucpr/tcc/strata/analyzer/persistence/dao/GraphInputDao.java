package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class GraphInputDao {

  static final String TYPES =
      """
      SELECT id, element_id, parent_pk, is_external
        FROM code_element
       WHERE analysis_pk = ? AND kind = ?
       ORDER BY element_id""";

  static final String PACKAGES =
      """
      SELECT id, element_id
        FROM code_element
       WHERE analysis_pk = ? AND kind = ?
       ORDER BY element_id""";

  static final String ROWS =
      """
      SELECT source_pk, target_pk, type, resolution, occurrences
        FROM dependency
       WHERE analysis_pk = ?
       ORDER BY source_pk""";

  private GraphInputDao() {}

  public static GraphSource read(Connection connection, long analysisPk, BooleanSupplier cancelled)
      throws SQLException {
    final List<GraphPackageRow> packages = packages(connection, analysisPk);
    final Map<Long, CodeElementId> packageIds = new HashMap<>();
    for (final GraphPackageRow row : packages) {
      packageIds.put(row.pk(), row.id());
    }
    final Types types = types(connection, analysisPk, packageIds);
    final List<Dependency> rows = rows(connection, analysisPk, types.ids(), cancelled);
    return new GraphSource(types.internal(), packages, rows);
  }

  private record Types(List<GraphTypeRow> internal, Map<Long, CodeElementId> ids) {}

  private static List<GraphPackageRow> packages(Connection connection, long analysisPk)
      throws SQLException {
    final List<GraphPackageRow> packages = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(PACKAGES)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, ElementKind.PACKAGE.name());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          packages.add(
              new GraphPackageRow(
                  rows.getLong("id"), new CodeElementId(rows.getString("element_id"))));
        }
      }
    }
    return packages;
  }

  private static Types types(
      Connection connection, long analysisPk, Map<Long, CodeElementId> packageIds)
      throws SQLException {
    final List<GraphTypeRow> internal = new ArrayList<>();
    final Map<Long, CodeElementId> ids = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(TYPES)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, ElementKind.TYPE.name());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final long pk = rows.getLong("id");
          final CodeElementId id = new CodeElementId(rows.getString("element_id"));
          ids.put(pk, id);
          if (rows.getInt("is_external") == 0) {
            internal.add(internalType(pk, id, nullableLong(rows, "parent_pk"), packageIds));
          }
        }
      }
    }
    return new Types(internal, ids);
  }

  private static GraphTypeRow internalType(
      long pk, CodeElementId id, Long parentPk, Map<Long, CodeElementId> packageIds) {
    final CodeElementId packageId = parentPk == null ? null : packageIds.get(parentPk);
    if (packageId == null) {
      throw new IllegalStateException(
          "o elemento de tipo interno " + pk + " não tem pacote como pai: " + parentPk);
    }
    return new GraphTypeRow(pk, id, parentPk, packageId);
  }

  private static List<Dependency> rows(
      Connection connection,
      long analysisPk,
      Map<Long, CodeElementId> typeIds,
      BooleanSupplier cancelled)
      throws SQLException {
    final List<Dependency> dependencies = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(ROWS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        long read = 0;
        while (rows.next()) {
          if (read % AnalysisConstants.GRAPH_READ_CHECK_STRIDE_ROWS == 0
              && cancelled.getAsBoolean()) {
            throw new CancellationException("análise cancelada no meio da leitura do grafo");
          }
          read++;
          dependencies.add(
              new Dependency(
                  typeId(typeIds, rows.getLong("source_pk")),
                  typeId(typeIds, rows.getLong("target_pk")),
                  DependencyType.valueOf(rows.getString("type")),
                  ResolutionQuality.valueOf(rows.getString("resolution")),
                  rows.getInt("occurrences")));
        }
      }
    }
    return dependencies;
  }

  private static CodeElementId typeId(Map<Long, CodeElementId> typeIds, long pk) {
    final CodeElementId id = typeIds.get(pk);
    if (id == null) {
      throw new IllegalStateException("uma ponta de dependência não é um elemento de tipo: " + pk);
    }
    return id;
  }

  private static Long nullableLong(ResultSet rows, String column) throws SQLException {
    final long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }
}
