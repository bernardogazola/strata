package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.nio.file.Path;

public final class PersistenceRoot {

  public static final String DIRECTORY_NAME = "repositories";

  private PersistenceRoot() {}

  public static PersistenceRoot prepare(Path dataDir) throws IOException {
    throw new UnsupportedOperationException("PersistenceRoot em implementação");
  }

  public Path directory() {
    throw new UnsupportedOperationException("PersistenceRoot em implementação");
  }

  Path cacheDirectory(RepositoryKey key) throws IOException {
    throw new UnsupportedOperationException("PersistenceRoot em implementação");
  }

  public boolean isInside(Path repositoryRoot) {
    throw new UnsupportedOperationException("PersistenceRoot em implementação");
  }
}
