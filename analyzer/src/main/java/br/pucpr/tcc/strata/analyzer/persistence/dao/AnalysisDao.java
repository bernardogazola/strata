package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class AnalysisDao {

  private static final String INSERT_RUNNING =
      """
      INSERT INTO analysis (analysis_id, status, started_at, head_commit_id, analyzer_version,
                            schema_version, protocol_version, config_hash, config_json,
                            source_fingerprint, include_tests, history_scope,
                            history_scope_commits, total_files, skipped_files, parse_failures,
                            shallow_clone, history_truncated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING id""";

  private static final int INSERT_FIRST_COUNTER = 14;

  private static final String UPDATE_COUNTERS =
      """
      UPDATE analysis
         SET total_files = ?, skipped_files = ?, parse_failures = ?, shallow_clone = ?,
             history_truncated_at = ?
       WHERE id = ?""";

  private static final String FINISH =
      """
      UPDATE analysis
         SET status = ?, finished_at = ?, failure_code = ?, failure_detail = ?
       WHERE id = ? AND status = ?""";

  private static final String FIND_BY_ANALYSIS_ID =
      """
      SELECT id, analysis_id, status, started_at, finished_at, head_commit_id, analyzer_version,
             schema_version, protocol_version, config_hash, source_fingerprint, include_tests,
             history_scope, history_scope_commits, total_files, skipped_files, parse_failures,
             shallow_clone, history_truncated_at, failure_code, failure_detail
        FROM analysis
       WHERE analysis_id = ?""";

  private AnalysisDao() {}

  public static long insertRunning(Connection connection, NewAnalysis analysis)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_RUNNING)) {
      statement.setString(1, analysis.analysisId());
      statement.setString(2, AnalysisStatus.RUNNING.name());
      statement.setLong(3, analysis.startedAt());
      statement.setString(4, analysis.headCommitId());
      statement.setString(5, analysis.analyzerVersion());
      statement.setInt(6, analysis.schemaVersion());
      statement.setInt(7, analysis.protocolVersion());
      statement.setString(8, analysis.configHash());
      statement.setString(9, analysis.configJson());
      statement.setString(10, analysis.sourceFingerprint());
      statement.setInt(11, analysis.includeTests() ? 1 : 0);
      statement.setString(12, analysis.historyScope().name());
      statement.setObject(13, analysis.historyScopeCommits());
      bindCounters(statement, INSERT_FIRST_COUNTER, AnalysisCounters.none());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("INSERT INTO analysis não devolveu id");
        }
        return rows.getLong(1);
      }
    }
  }

  public static void updateCounters(
      Connection connection, long analysisPk, AnalysisCounters counters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_COUNTERS)) {
      bindCounters(statement, 1, counters);
      statement.setLong(6, analysisPk);
      if (statement.executeUpdate() == 0) {
        throw new SQLException("nenhuma linha em analysis com pk " + analysisPk);
      }
    }
  }

  public static void finish(
      Connection connection,
      long analysisPk,
      AnalysisStatus status,
      long finishedAt,
      String failureCode,
      String failureDetail)
      throws SQLException {
    Objects.requireNonNull(status, "status");
    if (!status.isTerminal()) {
      throw new IllegalArgumentException("não é um status terminal: " + status);
    }
    final boolean failed = status == AnalysisStatus.FAILED;
    if (failed != (failureCode != null) || failed != (failureDetail != null)) {
      throw new IllegalArgumentException(
          "failureCode e failureDetail existem se e somente se o status é FAILED: " + status);
    }
    try (PreparedStatement statement = connection.prepareStatement(FINISH)) {
      statement.setString(1, status.name());
      statement.setLong(2, finishedAt);
      statement.setString(3, failureCode);
      statement.setString(4, failureDetail);
      statement.setLong(5, analysisPk);
      statement.setString(6, AnalysisStatus.RUNNING.name());
      if (statement.executeUpdate() == 0) {
        throw new SQLException("a análise de pk " + analysisPk + " não existe ou não está RUNNING");
      }
    }
  }

  public static Optional<AnalysisRecord> findByAnalysisId(Connection connection, String analysisId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ANALYSIS_ID)) {
      statement.setString(1, analysisId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(read(rows)) : Optional.empty();
      }
    }
  }

  private static void bindCounters(
      PreparedStatement statement, int firstIndex, AnalysisCounters counters) throws SQLException {
    statement.setInt(firstIndex, counters.totalFiles());
    statement.setInt(firstIndex + 1, counters.skippedFiles());
    statement.setInt(firstIndex + 2, counters.parseFailures());
    statement.setInt(firstIndex + 3, counters.shallowClone() ? 1 : 0);
    statement.setObject(firstIndex + 4, counters.historyTruncatedAt());
  }

  private static AnalysisRecord read(ResultSet rows) throws SQLException {
    return new AnalysisRecord(
        rows.getLong("id"),
        rows.getString("analysis_id"),
        AnalysisStatus.valueOf(rows.getString("status")),
        rows.getLong("started_at"),
        nullableLong(rows, "finished_at"),
        rows.getString("head_commit_id"),
        rows.getString("analyzer_version"),
        rows.getInt("schema_version"),
        rows.getInt("protocol_version"),
        rows.getString("config_hash"),
        rows.getString("source_fingerprint"),
        rows.getInt("include_tests") == 1,
        HistoryScope.valueOf(rows.getString("history_scope")),
        nullableInt(rows, "history_scope_commits"),
        new AnalysisCounters(
            rows.getInt("total_files"),
            rows.getInt("skipped_files"),
            rows.getInt("parse_failures"),
            rows.getInt("shallow_clone") == 1,
            nullableInt(rows, "history_truncated_at")),
        rows.getString("failure_code"),
        rows.getString("failure_detail"));
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
