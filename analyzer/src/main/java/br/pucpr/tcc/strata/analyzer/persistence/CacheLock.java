package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CacheLock implements AutoCloseable {

  static final String FILE_NAME = "analysis.lock";

  private static final long FIRST_BACKOFF_MILLIS = 20;

  private static final long MAX_BACKOFF_MILLIS = 250;

  private static final Set<Path> HELD_IN_THIS_PROCESS = ConcurrentHashMap.newKeySet();

  private final Path lockFile;
  private final FileChannel channel;
  private final FileLock lock;
  private final AtomicBoolean closed = new AtomicBoolean();

  private CacheLock(Path lockFile, FileChannel channel, FileLock lock) {
    this.lockFile = lockFile;
    this.channel = channel;
    this.lock = lock;
  }

  static CacheLock acquire(Path cacheDirectory, Duration patience)
      throws CacheUnavailableException, IOException {
    return acquire(cacheDirectory, patience, FileChannel::tryLock);
  }

  static CacheLock acquire(Path cacheDirectory, Duration patience, LockAttempt attempt)
      throws CacheUnavailableException, IOException {
    Objects.requireNonNull(patience, "patience");
    Objects.requireNonNull(attempt, "attempt");
    final Path lockFile = lockFileOf(cacheDirectory);
    if (!HELD_IN_THIS_PROCESS.add(lockFile)) {
      throw new IllegalStateException("este processo já segura o lock do cache " + lockFile);
    }
    final FileChannel channel;
    try {
      channel =
          FileChannel.open(
              lockFile,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | RuntimeException failure) {
      HELD_IN_THIS_PROCESS.remove(lockFile);
      throw failure;
    }
    try {
      return new CacheLock(lockFile, channel, waitFor(channel, patience, attempt, lockFile));
    } catch (CacheUnavailableException | IOException | RuntimeException failure) {
      closeAfter(channel, failure);
      HELD_IN_THIS_PROCESS.remove(lockFile);
      throw failure;
    }
  }

  static boolean isHeldInThisProcess(Path cacheDirectory) throws IOException {
    return HELD_IN_THIS_PROCESS.contains(lockFileOf(cacheDirectory));
  }

  @FunctionalInterface
  interface LockAttempt {

    FileLock tryLock(FileChannel channel) throws IOException;
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    IOException failure = null;
    try {
      lock.release();
    } catch (IOException releaseFailure) {
      failure = releaseFailure;
    }
    try {
      channel.close();
    } catch (IOException closeFailure) {
      if (failure == null) {
        failure = closeFailure;
      } else {
        failure.addSuppressed(closeFailure);
      }
    }
    HELD_IN_THIS_PROCESS.remove(lockFile);
    if (failure != null) {
      throw failure;
    }
  }

  private static Path lockFileOf(Path cacheDirectory) throws IOException {
    return cacheDirectory.toRealPath().resolve(FILE_NAME);
  }

  private static FileLock waitFor(
      FileChannel channel, Duration patience, LockAttempt attempt, Path lockFile)
      throws CacheUnavailableException, IOException {
    final long deadline = System.nanoTime() + patience.toNanos();
    long backoffMillis = FIRST_BACKOFF_MILLIS;
    FileLock lock = tryOnce(channel, attempt, lockFile);
    while (lock == null) {
      final long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new CacheUnavailableException(
            CacheUnavailableException.Kind.LOCKED,
            "outro processo ainda segura " + lockFile + " depois de " + patience.toMillis() + " ms",
            null);
      }
      pause(Math.min(backoffMillis, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1), lockFile);
      backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
      lock = tryOnce(channel, attempt, lockFile);
    }
    return lock;
  }

  private static FileLock tryOnce(FileChannel channel, LockAttempt attempt, Path lockFile)
      throws IOException {
    try {
      return attempt.tryLock(channel);
    } catch (OverlappingFileLockException bypassed) {
      throw new IllegalStateException(
          "o lock do cache " + lockFile + " foi pego neste processo por fora do CacheLock",
          bypassed);
    }
  }

  private static void pause(long millis, Path lockFile) throws CacheUnavailableException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.LOCKED,
          "interrompido ao aguardar " + lockFile,
          interrupted);
    }
  }

  private static void closeAfter(FileChannel channel, Exception original) {
    try {
      channel.close();
    } catch (IOException cleanup) {
      original.addSuppressed(cleanup);
    }
  }
}
