package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class StructureDao {

  private static final String IS_SERVABLE =
      "SELECT 1 FROM analysis WHERE id = ? AND status IN ('RUNNING','COMPLETED')";

  private static final String FIND_ELEMENT =
      """
      SELECT id, element_id, parent_pk, qualified_name, is_external
        FROM code_element
       WHERE analysis_pk = ? AND element_id = ?""";

  private static final String PACKAGE_NAME_BOUNDS =
      """
      SELECT (SELECT min(sort_key) FROM code_element
               WHERE analysis_pk = ?1 AND parent_pk IS NULL AND kind = 'PACKAGE'
                 AND sort_key > ''),
             (SELECT max(sort_key) FROM code_element
               WHERE analysis_pk = ?1 AND parent_pk IS NULL AND kind = 'PACKAGE'
                 AND sort_key > '')""";

  private StructureDao() {}

  public static boolean isServable(Connection connection, long analysisPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(IS_SERVABLE)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  public static Optional<StructureElementRow> findElement(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND_ELEMENT)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, elementId.value());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        final long parentValue = rows.getLong("parent_pk");
        final Long parentPk = rows.wasNull() ? null : parentValue;
        return Optional.of(
            new StructureElementRow(
                rows.getLong("id"),
                new CodeElementId(rows.getString("element_id")),
                parentPk,
                rows.getString("qualified_name"),
                rows.getInt("is_external") == 1));
      }
    }
  }

  public static Optional<PackageNameBounds> packageNameBounds(
      Connection connection, long analysisPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PACKAGE_NAME_BOUNDS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        final String first = rows.getString(1);
        final String last = rows.getString(2);
        return first == null ? Optional.empty() : Optional.of(new PackageNameBounds(first, last));
      }
    }
  }

  public static boolean packageExists(Connection connection, long analysisPk, String qualifiedName)
      throws SQLException {
    return findElement(connection, analysisPk, CodeElementId.ofPackage(qualifiedName)).isPresent();
  }
}
