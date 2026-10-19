package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class CacheMetadataDao {

  private static final String INSERT =
      """
      INSERT INTO cache_metadata (id, repository_key, schema_version, analyzer_version,
                                  protocol_version, created_at, updated_at)
      VALUES (1, ?, ?, ?, ?, ?, ?)""";

  private static final String FIND =
      """
      SELECT repository_key, schema_version, analyzer_version, protocol_version,
             created_at, updated_at
        FROM cache_metadata
       WHERE id = 1""";

  private static final String TOUCH =
      """
      UPDATE cache_metadata
         SET schema_version = ?, analyzer_version = ?, updated_at = ?
       WHERE id = 1""";

  private CacheMetadataDao() {}

  public static void insert(Connection connection, CacheMetadata metadata) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setString(1, metadata.repositoryKey());
      statement.setInt(2, metadata.schemaVersion());
      statement.setString(3, metadata.analyzerVersion());
      statement.setInt(4, metadata.protocolVersion());
      statement.setLong(5, metadata.createdAt());
      statement.setLong(6, metadata.updatedAt());
      statement.executeUpdate();
    }
  }

  public static Optional<CacheMetadata> find(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new CacheMetadata(
              rows.getString("repository_key"),
              rows.getInt("schema_version"),
              rows.getString("analyzer_version"),
              rows.getInt("protocol_version"),
              rows.getLong("created_at"),
              rows.getLong("updated_at")));
    }
  }

  public static void touch(
      Connection connection, int schemaVersion, String analyzerVersion, long updatedAt)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TOUCH)) {
      statement.setInt(1, schemaVersion);
      statement.setString(2, analyzerVersion);
      statement.setLong(3, updatedAt);
      if (statement.executeUpdate() == 0) {
        throw new SQLException("cache_metadata não tem linha para atualizar");
      }
    }
  }
}
