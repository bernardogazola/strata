package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.List;

public final class CacheRecovery {

  enum Verdict {
    CORRUPT,
    ENVIRONMENT,
    BUG
  }

  private CacheRecovery() {}

  public static boolean isCorruption(SQLException failure) {
    return classify(failure) == Verdict.CORRUPT;
  }

  static Verdict classify(Throwable failure) {
    throw new UnsupportedOperationException("CacheRecovery em implementação");
  }

  static Path quarantine(Path databaseFile, InstantSource clock) throws IOException {
    throw new UnsupportedOperationException("CacheRecovery em implementação");
  }

  static List<Path> backups(Path cacheDirectory) throws IOException {
    throw new UnsupportedOperationException("CacheRecovery em implementação");
  }

  static void retain(Path cacheDirectory, int keep) {
    throw new UnsupportedOperationException("CacheRecovery em implementação");
  }
}
