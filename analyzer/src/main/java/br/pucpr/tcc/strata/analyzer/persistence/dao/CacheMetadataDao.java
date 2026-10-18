package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class CacheMetadataDao {

  private CacheMetadataDao() {}

  public static void insert(Connection connection, CacheMetadata metadata) throws SQLException {
    throw new UnsupportedOperationException("CacheMetadataDao em implementação");
  }

  public static Optional<CacheMetadata> find(Connection connection) throws SQLException {
    throw new UnsupportedOperationException("CacheMetadataDao em implementação");
  }

  public static void touch(
      Connection connection, int schemaVersion, String analyzerVersion, long updatedAt)
      throws SQLException {
    throw new UnsupportedOperationException("CacheMetadataDao em implementação");
  }
}
