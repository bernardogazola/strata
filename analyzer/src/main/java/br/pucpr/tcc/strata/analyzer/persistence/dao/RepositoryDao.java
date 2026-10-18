package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class RepositoryDao {

  private static final String FIND =
      """
      SELECT repository_key, display_name, path_fingerprint, root_commit_id, default_branch,
             is_shallow, build_system, first_seen_at, last_seen_at
        FROM repository
       WHERE id = 1""";

  private static final String UPSERT =
      """
      INSERT INTO repository (id, repository_key, display_name, path_fingerprint,
                              root_commit_id, default_branch, is_shallow, build_system,
                              first_seen_at, last_seen_at)
      VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET
        repository_key   = excluded.repository_key,
        display_name     = excluded.display_name,
        path_fingerprint = excluded.path_fingerprint,
        root_commit_id   = excluded.root_commit_id,
        default_branch   = excluded.default_branch,
        is_shallow       = excluded.is_shallow,
        build_system     = excluded.build_system,
        last_seen_at     = excluded.last_seen_at""";

  private RepositoryDao() {}

  public static Optional<RepositoryRecord> find(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FIND);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new RepositoryRecord(
              rows.getString("repository_key"),
              rows.getString("display_name"),
              rows.getString("path_fingerprint"),
              rows.getString("root_commit_id"),
              rows.getString("default_branch"),
              rows.getInt("is_shallow") == 1,
              BuildSystem.valueOf(rows.getString("build_system")),
              rows.getLong("first_seen_at"),
              rows.getLong("last_seen_at")));
    }
  }

  public static void upsert(Connection connection, RepositoryRecord repository)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
      statement.setString(1, repository.repositoryKey());
      statement.setString(2, repository.displayName());
      statement.setString(3, repository.pathFingerprint());
      statement.setString(4, repository.rootCommitId());
      statement.setString(5, repository.defaultBranch());
      statement.setInt(6, repository.shallow() ? 1 : 0);
      statement.setString(7, repository.buildSystem().name());
      statement.setLong(8, repository.firstSeenAt());
      statement.setLong(9, repository.lastSeenAt());
      statement.executeUpdate();
    }
  }
}
