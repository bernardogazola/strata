package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.time.Duration;

final class CacheLock implements AutoCloseable {

  static final String FILE_NAME = "analysis.lock";

  private CacheLock() {}

  static CacheLock acquire(Path cacheDirectory, Duration patience)
      throws CacheUnavailableException, IOException {
    return acquire(cacheDirectory, patience, FileChannel::tryLock);
  }

  static CacheLock acquire(Path cacheDirectory, Duration patience, LockAttempt attempt)
      throws CacheUnavailableException, IOException {
    throw new UnsupportedOperationException("CacheLock em implementação");
  }

  @FunctionalInterface
  interface LockAttempt {

    FileLock tryLock(FileChannel channel) throws IOException;
  }

  @Override
  public void close() throws IOException {
    throw new UnsupportedOperationException("CacheLock em implementação");
  }
}
