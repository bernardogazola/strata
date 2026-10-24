package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class EdgeEvidenceDao {

  private EdgeEvidenceDao() {}

  public static boolean pairExists(
      Connection connection, long analysisPk, long sourcePk, long targetPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a conferência do par");
  }

  public static List<EdgeEvidenceRow> evidence(
      Connection connection, long analysisPk, long sourcePk, long targetPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a evidência gravada");
  }
}
