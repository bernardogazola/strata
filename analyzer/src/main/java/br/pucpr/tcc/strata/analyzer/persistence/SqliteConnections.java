package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.sqlite.SQLiteConfig;

public final class SqliteConnections implements AutoCloseable {

  private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";
  private static final String AUTO_VACUUM = "PRAGMA auto_vacuum";
  private static final String AUTO_VACUUM_INCREMENTAL = "2";
  private static final String OPTIMIZE = "PRAGMA optimize";
  private static final String CHECKPOINT_TRUNCATE = "PRAGMA wal_checkpoint(TRUNCATE)";
  private static final int CHECKPOINT_BUSY = 1;

  private final Path databaseFile;
  private final boolean created;
  private final Connection writer;

  private final ReentrantLock writerLock = new ReentrantLock();

  private final Semaphore readerPermits =
      new Semaphore(AnalysisConstants.SQLITE_READER_CONNECTIONS, true);

  private final Deque<Connection> idleReaders = new ArrayDeque<>();

  private final AtomicBoolean closed = new AtomicBoolean();

  private SqliteConnections(Path databaseFile, boolean created, Connection writer) {
    this.databaseFile = databaseFile;
    this.created = created;
    this.writer = writer;
  }

  public static SqliteConnections open(Path databaseFile) throws SQLException {
    final boolean created = absentOrEmpty(databaseFile);
    final Connection writer = openWriter(databaseFile);
    try {
      SqlitePragmas.applyToWriter(writer);
      if (created) {
        requireIncrementalAutoVacuum(writer);
      }
      return new SqliteConnections(databaseFile, created, writer);
    } catch (SQLException | RuntimeException failure) {
      closeAfter(writer, failure);
      throw failure;
    }
  }

  public boolean created() {
    return created;
  }

  public Path databaseFile() {
    return databaseFile;
  }

  public <T> T write(SqlWork<T> work) throws SQLException {
    requireNotNested();
    writerLock.lock();
    try {
      requireOpen();
      return SqlTransactions.run(writer, work);
    } finally {
      writerLock.unlock();
    }
  }

  public <T, E extends Exception> T withWriter(WriterWork<T, E> work) throws SQLException, E {
    requireNotNested();
    writerLock.lock();
    try {
      requireOpen();
      if (!writer.getAutoCommit()) {
        throw new IllegalStateException("a conexão de escrita não está em autocommit");
      }
      final T result;
      try {
        result = work.run(writer);
      } catch (Exception failure) {
        abandonOpenTransaction(failure);
        throw failure;
      }
      if (!writer.getAutoCommit()) {
        final IllegalStateException leftOpen =
            new IllegalStateException("o trabalho deixou uma transação aberta");
        abandonOpenTransaction(leftOpen);
        throw leftOpen;
      }
      return result;
    } finally {
      writerLock.unlock();
    }
  }

  public <T> T read(SqlWork<T> work) throws SQLException {
    readerPermits.acquireUninterruptibly();
    try {
      requireOpen();
      final Connection reader = borrowReader();
      final T result;
      try {
        result = SqlTransactions.run(reader, work);
      } catch (SQLException | RuntimeException failure) {
        closeAfter(reader, failure);
        throw failure;
      }
      giveBack(reader);
      return result;
    } finally {
      readerPermits.release();
    }
  }

  @Override
  public void close() throws SQLException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    writerLock.lock();
    readerPermits.acquireUninterruptibly(AnalysisConstants.SQLITE_READER_CONNECTIONS);
    try {
      closeInOrder();
    } finally {
      readerPermits.release(AnalysisConstants.SQLITE_READER_CONNECTIONS);
      writerLock.unlock();
    }
  }

  private void closeInOrder() throws SQLException {
    final Failures failures = new Failures();
    synchronized (idleReaders) {
      for (Connection reader = idleReaders.poll(); reader != null; reader = idleReaders.poll()) {
        failures.attempt(reader::close);
      }
    }
    failures.attempt(() -> optimize(writer));
    failures.attempt(() -> checkpoint(writer, databaseFile));
    failures.attempt(writer::close);
    failures.rethrow();
  }

  private void requireNotNested() {
    if (writerLock.isHeldByCurrentThread()) {
      throw new IllegalStateException(
          "já há uma unidade de trabalho na conexão de escrita rodando aqui");
    }
  }

  private void abandonOpenTransaction(Exception original) {
    try {
      if (writer.getAutoCommit()) {
        return;
      }
    } catch (SQLException cleanup) {
      original.addSuppressed(cleanup);
      return;
    }
    try {
      writer.rollback();
    } catch (SQLException cleanup) {
      original.addSuppressed(cleanup);
    }
    try {
      writer.setAutoCommit(true);
    } catch (SQLException cleanup) {
      original.addSuppressed(cleanup);
    }
  }

  private void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("as conexões do cache estão fechadas");
    }
  }

  private Connection borrowReader() throws SQLException {
    synchronized (idleReaders) {
      final Connection idle = idleReaders.poll();
      if (idle != null) {
        return idle;
      }
    }
    return openReader(databaseFile);
  }

  private void giveBack(Connection reader) {
    synchronized (idleReaders) {
      idleReaders.push(reader);
    }
  }

  private static boolean absentOrEmpty(Path databaseFile) {
    try {
      return Files.size(databaseFile) == 0L;
    } catch (NoSuchFileException absent) {
      return true;
    } catch (IOException unknown) {
      return false;
    }
  }

  private static Connection openWriter(Path databaseFile) throws SQLException {
    final SQLiteConfig config = new SQLiteConfig();
    config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    return config.createConnection(JDBC_URL_PREFIX + databaseFile);
  }

  private static Connection openReader(Path databaseFile) throws SQLException {
    final SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
    final Connection reader = config.createConnection(JDBC_URL_PREFIX + databaseFile);
    try {
      SqlitePragmas.applyToReader(reader);
      return reader;
    } catch (SQLException | RuntimeException failure) {
      closeAfter(reader, failure);
      throw failure;
    }
  }

  private static void requireIncrementalAutoVacuum(Connection writer) throws SQLException {
    final String mode;
    try (PreparedStatement statement = writer.prepareStatement(AUTO_VACUUM);
        ResultSet rows = statement.executeQuery()) {
      mode = rows.next() ? rows.getString(1) : null;
    }
    if (!AUTO_VACUUM_INCREMENTAL.equals(mode)) {
      throw new PragmaRefusedException(
          "auto_vacuum", AUTO_VACUUM_INCREMENTAL, String.valueOf(mode));
    }
  }

  private static void optimize(Connection writer) throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(OPTIMIZE)) {
      statement.execute();
    }
  }

  private static void checkpoint(Connection writer, Path databaseFile) throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(CHECKPOINT_TRUNCATE);
        ResultSet rows = statement.executeQuery()) {
      if (rows.next() && rows.getInt(1) == CHECKPOINT_BUSY) {
        Log.warn(
            "wal_checkpoint(TRUNCATE) no fechamento ficou ocupado (log="
                + rows.getInt(2)
                + ", checkpointed="
                + rows.getInt(3)
                + "); o -wal fica: "
                + databaseFile);
      }
    }
  }

  private static void closeAfter(Connection connection, Exception original) {
    try {
      connection.close();
    } catch (SQLException | RuntimeException cleanup) {
      original.addSuppressed(cleanup);
    }
  }

  @FunctionalInterface
  public interface WriterWork<T, E extends Exception> {

    T run(Connection writer) throws SQLException, E;
  }

  @FunctionalInterface
  private interface CloseStep {
    void run() throws SQLException;
  }

  private static final class Failures {

    private Exception first;

    void attempt(CloseStep step) {
      try {
        step.run();
      } catch (SQLException | RuntimeException failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }

    void rethrow() throws SQLException {
      if (first instanceof SQLException sql) {
        throw sql;
      }
      if (first instanceof RuntimeException unchecked) {
        throw unchecked;
      }
    }
  }
}
