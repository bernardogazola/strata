package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StageDao {

  private static final String UPSERT =
      """
      INSERT INTO analysis_stage (analysis_pk, stage, status, started_at, duration_ms,
                                  counters_json)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (analysis_pk, stage) DO UPDATE SET
        status        = excluded.status,
        started_at    = excluded.started_at,
        duration_ms   = excluded.duration_ms,
        counters_json = excluded.counters_json""";

  private static final String LIST =
      """
      SELECT stage, status, started_at, duration_ms, counters_json
        FROM analysis_stage
       WHERE analysis_pk = ?""";

  private StageDao() {}

  public static void upsert(Connection connection, long analysisPk, StageRecord stage)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, stage.stage().name());
      statement.setString(3, stage.status().name());
      statement.setObject(4, stage.startedAt());
      statement.setObject(5, stage.durationMs());
      statement.setString(6, stage.countersJson());
      statement.executeUpdate();
    }
  }

  public static List<StageRecord> list(Connection connection, long analysisPk) throws SQLException {
    final List<StageRecord> stages = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LIST)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          stages.add(
              new StageRecord(
                  AnalysisStage.valueOf(rows.getString("stage")),
                  StageStatus.valueOf(rows.getString("status")),
                  nullableLong(rows, "started_at"),
                  nullableLong(rows, "duration_ms"),
                  rows.getString("counters_json")));
        }
      }
    }
    return stages.stream().sorted(Comparator.comparing(StageRecord::stage)).toList();
  }

  private static Long nullableLong(ResultSet rows, String column) throws SQLException {
    final long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }
}
