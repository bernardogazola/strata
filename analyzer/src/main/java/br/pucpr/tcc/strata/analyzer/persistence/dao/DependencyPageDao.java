package br.pucpr.tcc.strata.analyzer.persistence.dao;

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
import java.util.List;

public final class DependencyPageDao {

  private static final String INBOUND =
      """
      SELECT o.id AS other_pk, o.element_id AS other_id, o.qualified_name AS other_name,
             o.is_external AS other_external, p.element_id AS package_id,
             p.qualified_name AS package_name, a.element_id AS anchor_id, d.type, d.resolution,
             d.occurrences, d.ambiguous
        FROM dependency d
        JOIN code_element a ON a.id = d.target_pk
        JOIN code_element o ON o.id = d.source_pk
        LEFT JOIN code_element p ON p.id = o.parent_pk
       WHERE d.target_pk = ? AND d.source_pk <> d.target_pk""";

  private static final String OUTBOUND =
      """
      SELECT o.id AS other_pk, o.element_id AS other_id, o.qualified_name AS other_name,
             o.is_external AS other_external, p.element_id AS package_id,
             p.qualified_name AS package_name, a.element_id AS anchor_id, d.type, d.resolution,
             d.occurrences, d.ambiguous
        FROM dependency d
        JOIN code_element a ON a.id = d.source_pk
        JOIN code_element o ON o.id = d.target_pk
        LEFT JOIN code_element p ON p.id = o.parent_pk
       WHERE d.source_pk = ? AND d.target_pk <> d.source_pk""";

  private static final String PACKAGE_INBOUND =
      """
      SELECT o.id AS other_pk, o.element_id AS other_id, o.qualified_name AS other_name,
             pe.source_pk, pe.target_pk, pe.type_pair_count, pe.occurrence_total, pe.resolution,
             pe.imports, pe.extends, pe.implements, pe.calls, pe.field_type, pe.parameter_type,
             pe.return_type, pe.annotation_reference
        FROM package_edge pe
        JOIN code_element o ON o.id = pe.source_pk
       WHERE pe.target_pk = ?""";

  private static final String PACKAGE_OUTBOUND =
      """
      SELECT o.id AS other_pk, o.element_id AS other_id, o.qualified_name AS other_name,
             pe.source_pk, pe.target_pk, pe.type_pair_count, pe.occurrence_total, pe.resolution,
             pe.imports, pe.extends, pe.implements, pe.calls, pe.field_type, pe.parameter_type,
             pe.return_type, pe.annotation_reference
        FROM package_edge pe
        JOIN code_element o ON o.id = pe.target_pk
       WHERE pe.source_pk = ?""";

  private static final String TYPE_TOTALS =
      """
      SELECT (SELECT count(DISTINCT d.source_pk)
                FROM dependency d
               WHERE d.target_pk = ?2 AND d.source_pk <> ?2) AS inbound,
             (SELECT count(DISTINCT d.target_pk)
                FROM dependency d
                JOIN code_element t ON t.id = d.target_pk
               WHERE d.source_pk = ?2 AND d.target_pk <> ?2 AND t.is_external = 0) AS outbound,
             (SELECT count(DISTINCT d.target_pk)
                FROM dependency d
                JOIN code_element t ON t.id = d.target_pk
               WHERE d.analysis_pk = ?1 AND d.source_pk = ?2 AND t.is_external = 1)
               AS outbound_external""";

  private static final String PACKAGE_TOTALS =
      """
      SELECT (SELECT count(*) FROM package_edge WHERE target_pk = ?1) AS inbound,
             (SELECT count(*) FROM package_edge WHERE source_pk = ?1) AS outbound""";

  private DependencyPageDao() {}

  public static List<TypeDependencyRow> inbound(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    return typeRows(connection, INBOUND, typePk, true);
  }

  public static List<TypeDependencyRow> outbound(
      Connection connection, long analysisPk, long typePk) throws SQLException {
    return typeRows(connection, OUTBOUND, typePk, false);
  }

  public static List<PackageDependencyRow> packageInbound(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    return packageRows(connection, PACKAGE_INBOUND, packagePk);
  }

  public static List<PackageDependencyRow> packageOutbound(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    return packageRows(connection, PACKAGE_OUTBOUND, packagePk);
  }

  public static DependencyTotalsRow typeTotals(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TYPE_TOTALS)) {
      statement.setLong(1, analysisPk);
      statement.setLong(2, typePk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return new DependencyTotalsRow(
            rows.getInt("inbound"), rows.getInt("outbound"), rows.getInt("outbound_external"));
      }
    }
  }

  public static DependencyTotalsRow packageTotals(
      Connection connection, long analysisPk, long packagePk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PACKAGE_TOTALS)) {
      statement.setLong(1, packagePk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return new DependencyTotalsRow(rows.getInt("inbound"), rows.getInt("outbound"), null);
      }
    }
  }

  private static List<TypeDependencyRow> typeRows(
      Connection connection, String sql, long typePk, boolean inbound) throws SQLException {
    final List<TypeDependencyRow> dependencies = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, typePk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final DependencyEndRow other = otherEnd(rows, rows.getInt("other_external") == 1);
          final CodeElementId anchor = new CodeElementId(rows.getString("anchor_id"));
          final Dependency dependency =
              new Dependency(
                  inbound ? other.elementId() : anchor,
                  inbound ? anchor : other.elementId(),
                  DependencyType.valueOf(rows.getString("type")),
                  ResolutionQuality.valueOf(rows.getString("resolution")),
                  rows.getInt("occurrences"));
          dependencies.add(new TypeDependencyRow(dependency, rows.getInt("ambiguous") == 1, other));
        }
      }
    }
    return List.copyOf(dependencies);
  }

  private static List<PackageDependencyRow> packageRows(
      Connection connection, String sql, long packagePk) throws SQLException {
    final List<PackageDependencyRow> edges = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, packagePk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          edges.add(new PackageDependencyRow(PackageEdgeDao.read(rows), otherEnd(rows, false)));
        }
      }
    }
    return List.copyOf(edges);
  }

  private static DependencyEndRow otherEnd(ResultSet rows, boolean isExternal) throws SQLException {
    final CodeElementId elementId = new CodeElementId(rows.getString("other_id"));
    String packageId = null;
    String packageName = null;
    if (!isExternal && elementId.kind() == ElementKind.TYPE) {
      packageId = rows.getString("package_id");
      packageName = rows.getString("package_name");
    }
    return new DependencyEndRow(
        rows.getLong("other_pk"),
        elementId,
        rows.getString("other_name"),
        isExternal,
        packageId == null ? null : new CodeElementId(packageId),
        packageName);
  }
}
