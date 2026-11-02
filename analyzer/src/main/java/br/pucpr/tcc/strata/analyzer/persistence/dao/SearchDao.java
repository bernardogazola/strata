package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class SearchDao {

  private static final String SEARCH =
      """
      WITH hits AS (
        SELECT e.id, e.element_id,
               CASE WHEN e.search_name = ?2 THEN 0
                    WHEN instr(e.search_name, ?2) = 1 THEN 1
                    ELSE 2 END AS tier,
               e.kind = 'PACKAGE' AS kind_rank,
               length(e.search_name) AS len
          FROM code_element e INDEXED BY idx_element_search
         WHERE e.analysis_pk = ?1
           AND e.kind IN ('PACKAGE','TYPE') AND e.is_external = 0
           AND e.kind IN (?3, ?4)
           AND instr(e.search_name, ?2) > 0
         ORDER BY tier, kind_rank, len, e.element_id
         LIMIT ?5)
      SELECT x.element_id, x.qualified_name,
             p.element_id AS package_id, p.qualified_name AS package_name
        FROM hits h
        JOIN code_element x ON x.id = h.id
        LEFT JOIN code_element p ON p.id = x.parent_pk AND x.kind = 'TYPE'
       ORDER BY h.tier, h.kind_rank, h.len, h.element_id""";

  private SearchDao() {}

  public static List<SearchRow> search(
      Connection connection, long analysisPk, String folded, Set<ElementKind> kinds, int limit)
      throws SQLException {
    if (folded.isEmpty() || kinds.isEmpty()) {
      throw new IllegalArgumentException(
          "uma busca pede um termo não vazio e pelo menos um tipo de elemento");
    }
    final List<ElementKind> bound = List.copyOf(kinds);
    final List<SearchRow> hits = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SEARCH)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, folded);
      statement.setString(3, bound.getFirst().name());
      statement.setString(4, bound.getLast().name());
      statement.setInt(5, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final String packageId = rows.getString("package_id");
          hits.add(
              new SearchRow(
                  new CodeElementId(rows.getString("element_id")),
                  rows.getString("qualified_name"),
                  packageId == null ? null : new CodeElementId(packageId),
                  rows.getString("package_name")));
        }
      }
    }
    return List.copyOf(hits);
  }
}
