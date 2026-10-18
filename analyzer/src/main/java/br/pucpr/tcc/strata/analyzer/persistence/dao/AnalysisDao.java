package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class AnalysisDao {

  private AnalysisDao() {}

  public static long insertRunning(Connection connection, NewAnalysis analysis)
      throws SQLException {
    throw new UnsupportedOperationException("AnalysisDao em implementação");
  }

  public static void updateCounters(
      Connection connection, long analysisPk, AnalysisCounters counters) throws SQLException {
    throw new UnsupportedOperationException("AnalysisDao em implementação");
  }

  public static void finish(
      Connection connection,
      long analysisPk,
      AnalysisStatus status,
      long finishedAt,
      String failureCode,
      String failureDetail)
      throws SQLException {
    throw new UnsupportedOperationException("AnalysisDao em implementação");
  }

  public static Optional<AnalysisRecord> findByAnalysisId(Connection connection, String analysisId)
      throws SQLException {
    throw new UnsupportedOperationException("AnalysisDao em implementação");
  }
}
