package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.List;
import org.sqlite.SQLiteException;

public final class CacheRecovery {

  enum Verdict {
    CORRUPT,
    ENVIRONMENT,
    BUG
  }

  private static final int PRIMARY_CODE_MASK = 0xFF;

  private static final int SQLITE_ERROR = 1;
  private static final int SQLITE_CORRUPT = 11;
  private static final int SQLITE_CONSTRAINT = 19;
  private static final int SQLITE_MISMATCH = 20;
  private static final int SQLITE_NOTADB = 26;

  private CacheRecovery() {}

  public static boolean isCorruption(SQLException failure) {
    return classify(failure) == Verdict.CORRUPT;
  }

  static Verdict classify(Throwable failure) {
    if (!(failure instanceof SQLiteException sqlite)) {
      return Verdict.BUG;
    }
    return switch (sqlite.getResultCode().code & PRIMARY_CODE_MASK) {
      case SQLITE_CORRUPT, SQLITE_NOTADB -> Verdict.CORRUPT;
      case SQLITE_ERROR, SQLITE_CONSTRAINT, SQLITE_MISMATCH -> Verdict.BUG;
      default -> Verdict.ENVIRONMENT;
    };
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
