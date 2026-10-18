package br.pucpr.tcc.strata.analyzer.persistence;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.InstantSource;

public final class RepositoryCache implements AutoCloseable {

  public static final String DATABASE_FILE = "analysis.sqlite";

  private RepositoryCache() {}

  public static CacheOpening open(
      PersistenceRoot root,
      RepositoryIdentity identity,
      Path repositoryRoot,
      String analyzerVersion,
      InstantSource clock)
      throws CacheUnavailableException {
    return open(root, identity, repositoryRoot, analyzerVersion, clock, Migrations.bundled());
  }

  static CacheOpening open(
      PersistenceRoot root,
      RepositoryIdentity identity,
      Path repositoryRoot,
      String analyzerVersion,
      InstantSource clock,
      Migrations migrations)
      throws CacheUnavailableException {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  public RepositoryIdentity identity() {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  public SqliteConnections connections() {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  public boolean uncleanShutdown() {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  public boolean quickCheck() throws SQLException {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  public CacheOpening.Rebuilt rebuildAfterCorruption() throws CacheUnavailableException {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException("RepositoryCache em implementação");
  }
}
