package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class EdgeEvidenceDao {

  private static final String PAIR_EXISTS =
      """
      SELECT 1
        FROM dependency d
       WHERE d.analysis_pk = ? AND d.source_pk = ? AND d.target_pk = ?
       LIMIT 1""";

  private static final String EVIDENCE =
      """
      SELECT d.type, ev.resolution, f.relative_path, ev.line,
             fm.element_id AS from_id, fo.element_id AS from_owner_id,
             fo.simple_name AS from_owner_simple, fo.qualified_name AS from_owner_name,
             fp.qualified_name AS from_owner_package,
             tm.element_id AS to_id, tt.element_id AS to_owner_id,
             tt.simple_name AS to_owner_simple, tt.qualified_name AS to_owner_name,
             tp.qualified_name AS to_owner_package
        FROM dependency d
        JOIN dependency_evidence ev ON ev.dependency_pk = d.id
        LEFT JOIN source_file f ON f.id = ev.file_pk
        LEFT JOIN code_element fm ON fm.id = ev.from_method_pk
        LEFT JOIN code_element fo ON fo.id = fm.parent_pk
        LEFT JOIN code_element fp ON fp.id = fo.parent_pk
        LEFT JOIN code_element tm ON tm.id = ev.to_method_pk
        LEFT JOIN code_element tt ON tt.id = tm.parent_pk
        LEFT JOIN code_element tp ON tp.id = tt.parent_pk
       WHERE d.analysis_pk = ? AND d.source_pk = ? AND d.target_pk = ?""";

  private static final String FROM = "from";

  private static final String TO = "to";

  private EdgeEvidenceDao() {}

  public static boolean pairExists(
      Connection connection, long analysisPk, long sourcePk, long targetPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PAIR_EXISTS)) {
      bindPair(statement, analysisPk, sourcePk, targetPk);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  public static List<EdgeEvidenceRow> evidence(
      Connection connection, long analysisPk, long sourcePk, long targetPk) throws SQLException {
    final List<EdgeEvidenceRow> evidence = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(EVIDENCE)) {
      bindPair(statement, analysisPk, sourcePk, targetPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          evidence.add(
              new EdgeEvidenceRow(
                  DependencyType.valueOf(rows.getString("type")),
                  ResolutionQuality.valueOf(rows.getString("resolution")),
                  rows.getString("relative_path"),
                  Columns.nullableInt(rows, "line"),
                  method(rows, FROM),
                  method(rows, TO)));
        }
      }
    }
    return List.copyOf(evidence);
  }

  private static void bindPair(
      PreparedStatement statement, long analysisPk, long sourcePk, long targetPk)
      throws SQLException {
    statement.setLong(1, analysisPk);
    statement.setLong(2, sourcePk);
    statement.setLong(3, targetPk);
  }

  private static EvidenceMethodRow method(ResultSet rows, String prefix) throws SQLException {
    final String methodId = rows.getString(prefix + "_id");
    if (methodId == null) {
      return null;
    }
    return new EvidenceMethodRow(
        new CodeElementId(methodId),
        new CodeElementId(rows.getString(prefix + "_owner_id")),
        rows.getString(prefix + "_owner_simple"),
        rows.getString(prefix + "_owner_name"),
        rows.getString(prefix + "_owner_package"));
  }
}
