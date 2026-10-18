package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class StageDao {

  private StageDao() {}

  public static void upsert(Connection connection, long analysisPk, StageRecord stage)
      throws SQLException {
    throw new UnsupportedOperationException("StageDao em implementação");
  }

  public static List<StageRecord> list(Connection connection, long analysisPk) throws SQLException {
    throw new UnsupportedOperationException("StageDao em implementação");
  }
}
