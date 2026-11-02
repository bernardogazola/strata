package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

final class Columns {

  private Columns() {}

  static Integer nullableInt(ResultSet rows, String column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }
}
