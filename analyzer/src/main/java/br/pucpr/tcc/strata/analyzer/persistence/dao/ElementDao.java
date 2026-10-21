package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

public final class ElementDao {

  private static final String INSERT_HEAD =
      """
      INSERT INTO code_element (id, analysis_pk, element_id, kind, parent_pk, simple_name,
                                qualified_name, sort_key, type_kind, modifiers, is_external,
                                is_test, source_root_pk, file_pk, start_line, end_line)
      VALUES""";

  private static final int COLUMNS = 16;

  private static final String NEXT_ID = "SELECT coalesce(max(id), 0) + 1 FROM code_element";

  private static final String LIST =
      """
      SELECT id, element_id, kind, parent_pk, simple_name, qualified_name, sort_key, type_kind,
             modifiers, is_external, is_test, source_root_pk, file_pk, start_line, end_line
        FROM code_element
       WHERE analysis_pk = ?
       ORDER BY id""";

  private static final String FIND_PK =
      "SELECT id FROM code_element WHERE analysis_pk = ? AND element_id = ?";

  private static final String MARK_TEST_PACKAGES =
      """
      UPDATE code_element AS package
         SET is_test = (SELECT coalesce(min(child.is_test), 0)
                          FROM code_element AS child
                         WHERE child.analysis_pk = package.analysis_pk
                           AND child.parent_pk = package.id
                           AND child.kind = ?)
       WHERE package.analysis_pk = ? AND package.kind = ?""";

  private ElementDao() {}

  public static long nextId(Connection connection) throws SQLException {
    return MultiRowInsert.nextId(connection, NEXT_ID);
  }

  public static void insert(Connection connection, long analysisPk, List<ElementRow> rows)
      throws SQLException {
    insert(connection, analysisPk, rows, MultiRowInsert.NEVER_CANCELLED);
  }

  public static void insert(
      Connection connection, long analysisPk, List<ElementRow> rows, BooleanSupplier cancelled)
      throws SQLException {
    MultiRowInsert.insertRows(
        connection,
        INSERT_HEAD,
        COLUMNS,
        rows,
        (statement, first, row) -> {
          statement.setLong(first, row.pk());
          statement.setLong(first + 1, analysisPk);
          statement.setString(first + 2, row.elementId().value());
          statement.setString(first + 3, row.kind().name());
          statement.setObject(first + 4, row.parentPk());
          statement.setString(first + 5, row.simpleName());
          statement.setString(first + 6, row.qualifiedName());
          statement.setString(first + 7, row.sortKey());
          statement.setString(first + 8, row.typeKind() == null ? null : row.typeKind().name());
          statement.setString(first + 9, row.modifiers());
          statement.setInt(first + 10, row.isExternal() ? 1 : 0);
          statement.setInt(first + 11, row.isTest() ? 1 : 0);
          statement.setObject(first + 12, row.sourceRootPk());
          statement.setObject(first + 13, row.filePk());
          statement.setObject(first + 14, row.startLine());
          statement.setObject(first + 15, row.endLine());
        },
        cancelled);
  }

  public static List<ElementRow> list(Connection connection, long analysisPk) throws SQLException {
    final List<ElementRow> elements = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          elements.add(read(rows));
        }
      }
    }
    return List.copyOf(elements);
  }

  public static OptionalLong findPk(Connection connection, long analysisPk, CodeElementId elementId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND_PK)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, elementId.value());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  public static void markTestPackages(Connection connection, long analysisPk) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(MARK_TEST_PACKAGES)) {
      statement.setString(1, ElementKind.TYPE.name());
      statement.setLong(2, analysisPk);
      statement.setString(3, ElementKind.PACKAGE.name());
      statement.executeUpdate();
    }
  }

  private static ElementRow read(ResultSet rows) throws SQLException {
    final String typeKind = rows.getString("type_kind");
    return new ElementRow(
        rows.getLong("id"),
        new CodeElementId(rows.getString("element_id")),
        ElementKind.valueOf(rows.getString("kind")),
        nullableLong(rows, "parent_pk"),
        rows.getString("simple_name"),
        rows.getString("qualified_name"),
        rows.getString("sort_key"),
        typeKind == null ? null : TypeKind.valueOf(typeKind),
        rows.getString("modifiers"),
        rows.getInt("is_external") == 1,
        rows.getInt("is_test") == 1,
        nullableLong(rows, "source_root_pk"),
        nullableLong(rows, "file_pk"),
        nullableInt(rows, "start_line"),
        nullableInt(rows, "end_line"));
  }

  private static Long nullableLong(ResultSet rows, String column) throws SQLException {
    final long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
