package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
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
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
