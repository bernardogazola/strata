package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class CacheMaintenanceDao {

  private static final String CHECKPOINT_PASSIVE = "PRAGMA wal_checkpoint(PASSIVE)";

  private static final int CHECKPOINT_BUSY = 1;

  private CacheMaintenanceDao() {}

  public static WalCheckpoint checkpointPassive(Connection writer) throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(CHECKPOINT_PASSIVE);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new SQLException("PRAGMA wal_checkpoint não devolveu linha");
      }
      return new WalCheckpoint(rows.getInt(1) == CHECKPOINT_BUSY, rows.getInt(2), rows.getInt(3));
    }
  }

  public record WalCheckpoint(boolean busy, int logFrames, int checkpointedFrames) {}
}
