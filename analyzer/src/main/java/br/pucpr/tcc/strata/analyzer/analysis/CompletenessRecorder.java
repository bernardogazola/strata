package br.pucpr.tcc.strata.analyzer.analysis;

import java.sql.SQLException;

public interface CompletenessRecorder {

  void recordSourceFiles(int total, int skipped, int parseFailures) throws SQLException;

  void recordHistoryTruncatedAt(int commits) throws SQLException;
}
