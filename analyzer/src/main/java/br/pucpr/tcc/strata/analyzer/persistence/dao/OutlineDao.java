package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class OutlineDao {

  private static final String ROOT_SELECT =
      """
      SELECT e.id, e.element_id, e.qualified_name,
             (SELECT count(*) FROM code_element t
               WHERE t.analysis_pk = e.analysis_pk AND t.parent_pk = e.id
                 AND t.kind = 'TYPE') AS child_count
        FROM code_element e
       WHERE e.analysis_pk = ? AND e.parent_pk IS NULL AND e.kind = 'PACKAGE'
         AND EXISTS (SELECT 1 FROM graph_metric g
                      WHERE g.element_pk = e.id AND g.granularity = 'PACKAGE')
      """;

  private static final String TYPE_SELECT =
      """
      SELECT e.id, e.element_id, e.qualified_name, 0 AS child_count
        FROM code_element e
       WHERE e.analysis_pk = ? AND e.parent_pk = ? AND e.kind = 'TYPE' AND e.is_external = 0
      """;

  private static final String AFTER_CURSOR =
      """
         AND e.sort_key > (SELECT k.sort_key FROM code_element k
                            WHERE k.id = ? AND k.analysis_pk = ? AND k.parent_pk IS ?
                              AND k.kind = ?)
      """;

  private static final String PAGE_ORDER = " ORDER BY e.sort_key LIMIT ?";

  private static final String IS_LISTED =
      """
      SELECT 1 FROM code_element k
       WHERE k.id = ? AND k.analysis_pk = ? AND k.parent_pk IS ? AND k.kind = ?""";

  private static final String IS_LISTED_AT_ROOT =
      IS_LISTED
          + """

         AND EXISTS (SELECT 1 FROM graph_metric g
                      WHERE g.element_pk = k.id AND g.granularity = 'PACKAGE')""";

  private OutlineDao() {}

  public static List<OutlineRow> rootPage(
      Connection connection, long analysisPk, Long afterPk, int fetch) throws SQLException {
    final String sql = ROOT_SELECT + (afterPk == null ? "" : AFTER_CURSOR) + PAGE_ORDER;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int next = 1;
      statement.setLong(next++, analysisPk);
      next = bindCursor(statement, next, analysisPk, afterPk, null, ElementKind.PACKAGE);
      statement.setInt(next, fetch);
      return rows(statement);
    }
  }

  public static List<OutlineRow> typePage(
      Connection connection, long analysisPk, long packagePk, Long afterPk, int fetch)
      throws SQLException {
    final String sql = TYPE_SELECT + (afterPk == null ? "" : AFTER_CURSOR) + PAGE_ORDER;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int next = 1;
      statement.setLong(next++, analysisPk);
      statement.setLong(next++, packagePk);
      next = bindCursor(statement, next, analysisPk, afterPk, packagePk, ElementKind.TYPE);
      statement.setInt(next, fetch);
      return rows(statement);
    }
  }

  public static boolean isListed(
      Connection connection, long analysisPk, long elementPk, Long parentPk, ElementKind kind)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(parentPk == null ? IS_LISTED_AT_ROOT : IS_LISTED)) {
      bindRow(statement, 1, elementPk, analysisPk, parentPk, kind);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static int bindCursor(
      PreparedStatement statement,
      int first,
      long analysisPk,
      Long afterPk,
      Long parentPk,
      ElementKind kind)
      throws SQLException {
    if (afterPk == null) {
      return first;
    }
    return bindRow(statement, first, afterPk, analysisPk, parentPk, kind);
  }

  private static int bindRow(
      PreparedStatement statement,
      int first,
      long elementPk,
      long analysisPk,
      Long parentPk,
      ElementKind kind)
      throws SQLException {
    statement.setLong(first, elementPk);
    statement.setLong(first + 1, analysisPk);
    if (parentPk == null) {
      statement.setNull(first + 2, Types.INTEGER);
    } else {
      statement.setLong(first + 2, parentPk);
    }
    statement.setString(first + 3, kind.name());
    return first + 4;
  }

  private static List<OutlineRow> rows(PreparedStatement statement) throws SQLException {
    final List<OutlineRow> page = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        page.add(
            new OutlineRow(
                rows.getLong("id"),
                new CodeElementId(rows.getString("element_id")),
                rows.getString("qualified_name"),
                rows.getInt("child_count")));
      }
    }
    return List.copyOf(page);
  }
}
