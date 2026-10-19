package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.util.Objects;

public record CacheMetadata(
    String repositoryKey,
    int schemaVersion,
    String analyzerVersion,
    int protocolVersion,
    long createdAt,
    long updatedAt) {

  public CacheMetadata {
    Objects.requireNonNull(repositoryKey, "repositoryKey");
    Objects.requireNonNull(analyzerVersion, "analyzerVersion");
  }
}
