package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections.ConnectionLeftOpenException;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CacheMetadata;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CacheMetadataDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RepositoryCache implements AutoCloseable {

  public static final String DATABASE_FILE = "analysis.sqlite";

  private static final SqlWork<Void> WRITE_PROBE = connection -> null;

  private static final String USER_VERSION = "PRAGMA user_version";

  private static final String HOLDS_ANY_ROW =
      "SELECT EXISTS (SELECT 1 FROM repository) OR EXISTS (SELECT 1 FROM analysis)";

  private static final String QUICK_CHECK = "PRAGMA quick_check";
  private static final List<String> QUICK_CHECK_HEALTHY = List.of("ok");

  private final Recipe recipe;
  private final Path directory;
  private final CacheLock lock;
  private final SqliteConnections connections;
  private final boolean uncleanShutdown;
  private final AtomicBoolean closed = new AtomicBoolean();

  private RepositoryCache(
      Recipe recipe,
      Path directory,
      CacheLock lock,
      SqliteConnections connections,
      boolean uncleanShutdown) {
    this.recipe = recipe;
    this.directory = directory;
    this.lock = lock;
    this.connections = connections;
    this.uncleanShutdown = uncleanShutdown;
  }

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
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(repositoryRoot, "repositoryRoot");
    final Recipe recipe = new Recipe(identity, analyzerVersion, clock, migrations);
    final Path realRepositoryRoot = realPathOf(repositoryRoot);
    if (root.isInside(realRepositoryRoot)) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.LOCATION_INSIDE_REPOSITORY,
          "o diretório do cache "
              + root.directory()
              + " está dentro do repositório analisado "
              + realRepositoryRoot,
          null);
    }
    final Path directory;
    final CacheLock lock;
    try {
      directory = root.cacheDirectory(identity.key());
      lock =
          CacheLock.acquire(directory, Duration.ofMillis(AnalysisConstants.CACHE_LOCK_WAIT_MILLIS));
    } catch (IOException failure) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível preparar o diretório do cache ou o lock de " + identity.key().value(),
          failure);
    }
    final CacheOpening opening;
    try {
      opening = openLocked(recipe, directory, lock);
    } catch (Throwable failure) {
      releaseAfter(lock, failure);
      throw failure;
    }
    Log.info(
        "repositoryKey="
            + identity.key().value()
            + " cache="
            + directory
            + " repository="
            + realRepositoryRoot);
    return opening;
  }

  public RepositoryIdentity identity() {
    return recipe.identity();
  }

  public SqliteConnections connections() {
    return connections;
  }

  public boolean uncleanShutdown() {
    return uncleanShutdown;
  }

  public boolean quickCheck() throws SQLException {
    final List<String> report;
    try {
      report = connections.read(RepositoryCache::quickCheckReport);
    } catch (SQLException failure) {
      if (CacheRecovery.isCorruption(failure)) {
        Log.warn("PRAGMA quick_check em " + connections.databaseFile() + " lançou " + failure);
        return false;
      }
      throw failure;
    }
    if (report.equals(QUICK_CHECK_HEALTHY)) {
      return true;
    }
    Log.warn(
        "PRAGMA quick_check em "
            + connections.databaseFile()
            + " relatou "
            + report.size()
            + " problema(s), o primeiro: "
            + report.getFirst().replace('\n', ' '));
    return false;
  }

  public CacheOpening.Rebuilt rebuildAfterCorruption() throws CacheUnavailableException {
    connections.requireOutsideUnits();
    if (!closed.compareAndSet(false, true)) {
      throw new IllegalStateException("o cache de " + directory + " já está fechado");
    }
    if (!closeConnections()) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "uma conexão com o cache em " + directory + " ainda está aberta; nada foi renomeado",
          null);
    }
    try {
      return rebuild(
          recipe,
          directory,
          lock,
          new CacheInvalidation.Corrupt(),
          "corrupção encontrada durante o uso");
    } catch (Throwable failure) {
      releaseAfter(lock, failure);
      throw failure;
    }
  }

  @Override
  public void close() {
    connections.requireOutsideUnits();
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (!closeConnections()) {
      return;
    }
    try {
      lock.close();
    } catch (IOException failure) {
      Log.warn("falha ao soltar o lock do cache de " + directory + ": " + failure);
    }
  }

  private static CacheOpening openLocked(Recipe recipe, Path directory, CacheLock lock)
      throws CacheUnavailableException {
    final Path database = directory.resolve(DATABASE_FILE);
    if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE, database + " não é um arquivo comum", null);
    }
    final boolean uncleanShutdown;
    try {
      uncleanShutdown = CacheRecovery.walHasFrames(database);
    } catch (IOException failure) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível ler o -wal de " + database,
          failure);
    }
    try {
      final Attempt first = attempt(database, recipe);
      final RepositoryCache cache =
          new RepositoryCache(recipe, directory, lock, first.connections(), uncleanShutdown);
      return first.created() ? new CacheOpening.Created(cache) : new CacheOpening.Reused(cache);
    } catch (ConnectionLeftOpenException leftOpen) {
      keepLockUntilExit(lock, directory, leftOpen);
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível abrir " + database + "; nada foi renomeado",
          leftOpen);
    } catch (CacheDiscardException discard) {
      return rebuild(recipe, directory, lock, discard.invalidation(), discard.toString());
    } catch (PragmaRefusedException refused) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.PRAGMA_REFUSED,
          "não foi possível abrir " + database,
          refused);
    } catch (SQLException failure) {
      return switch (CacheRecovery.classify(failure)) {
        case CORRUPT ->
            rebuild(recipe, directory, lock, new CacheInvalidation.Corrupt(), failure.toString());
        case ENVIRONMENT, BUG ->
            throw new CacheUnavailableException(
                CacheUnavailableException.Kind.STORAGE,
                "não foi possível abrir " + database,
                failure);
      };
    }
  }

  private static CacheOpening.Rebuilt rebuild(
      Recipe recipe, Path directory, CacheLock lock, CacheInvalidation invalidation, String reason)
      throws CacheUnavailableException {
    final Path database = directory.resolve(DATABASE_FILE);
    final Path backup;
    try {
      backup = CacheRecovery.quarantine(database, recipe.clock());
    } catch (CacheRecovery.IncompleteQuarantineException incomplete) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível terminar a quarentena de "
              + database
              + " (movido para "
              + incomplete.backup()
              + ")",
          incomplete,
          invalidation);
    } catch (IOException failure) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível pôr em quarentena " + database,
          failure);
    }
    Log.warn(
        "o cache "
            + database
            + " está "
            + invalidation.reason()
            + " ("
            + reason
            + "); movido para "
            + backup
            + " e reconstruído no lugar");
    CacheRecovery.retain(directory, AnalysisConstants.MAX_CACHE_BACKUPS, backup);
    final Attempt fresh;
    try {
      fresh = attempt(database, recipe);
    } catch (ConnectionLeftOpenException leftOpen) {
      keepLockUntilExit(lock, directory, leftOpen);
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "o cache reconstruído " + database + " não pôde ser criado",
          leftOpen,
          invalidation);
    } catch (SQLException | CacheDiscardException failure) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "o cache reconstruído " + database + " não pôde ser criado",
          failure,
          invalidation);
    }
    return new CacheOpening.Rebuilt(
        new RepositoryCache(recipe, directory, lock, fresh.connections(), false), invalidation);
  }

  private static Attempt attempt(Path database, Recipe recipe)
      throws SQLException, CacheDiscardException {
    final SqliteConnections connections = SqliteConnections.open(database);
    try {
      connections.write(WRITE_PROBE);
      final int found = connections.write(RepositoryCache::userVersion);
      final int reached =
          connections.withWriter(writer -> recipe.migrations().migrate(writer, recipe.clock()));
      if (found == 0) {
        connections.write(
            c -> {
              CacheMetadataDao.insert(c, recipe.freshMetadata(reached));
              return null;
            });
        return new Attempt(connections, true);
      }
      if (confirmIdentity(connections, recipe, reached)) {
        return new Attempt(connections, true);
      }
      if (found < reached) {
        connections.write(
            c -> {
              CacheMetadataDao.touch(c, reached, recipe.analyzerVersion(), recipe.clock().millis());
              return null;
            });
      }
      return new Attempt(connections, false);
    } catch (Throwable failure) {
      closeAfter(connections, failure);
      if (!connections.allClosed()) {
        throw new ConnectionLeftOpenException(database, failure);
      }
      throw failure;
    }
  }

  private static boolean confirmIdentity(SqliteConnections connections, Recipe recipe, int reached)
      throws SQLException, CacheDiscardException {
    final RepositoryIdentity identity = recipe.identity();
    final String key = identity.key().value();
    final Optional<CacheMetadata> metadata = connections.write(CacheMetadataDao::find);
    if (metadata.isEmpty()) {
      final CacheMetadata fresh = recipe.freshMetadata(reached);
      if (connections.write(c -> resumeInterruptedCreation(c, fresh))) {
        return true;
      }
      throw new CacheDiscardException(
          new CacheInvalidation.Corrupt(), "cache_metadata está vazia em um cache que tem linhas");
    }
    final boolean sameKey =
        metadata.map(CacheMetadata::repositoryKey).filter(key::equals).isPresent();
    if (!sameKey) {
      throw new CacheDiscardException(
          new CacheInvalidation.Corrupt(), "cache_metadata não nomeia a repositoryKey " + key);
    }
    final boolean collides =
        connections
            .write(RepositoryDao::find)
            .filter(repository -> !isSameRepository(repository, identity))
            .isPresent();
    if (collides) {
      throw new CacheDiscardException(
          new CacheInvalidation.Corrupt(),
          "a linha de repositório de " + key + " tem outro (path_fingerprint, root_commit_id)");
    }
    return false;
  }

  private static boolean resumeInterruptedCreation(Connection writer, CacheMetadata metadata)
      throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(HOLDS_ANY_ROW);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new IllegalStateException("nenhuma linha de " + HOLDS_ANY_ROW);
      }
      if (rows.getBoolean(1)) {
        return false;
      }
    }
    CacheMetadataDao.insert(writer, metadata);
    return true;
  }

  private static boolean isSameRepository(
      RepositoryRecord repository, RepositoryIdentity identity) {
    return identity.pathFingerprint().value().equals(repository.pathFingerprint())
        && Objects.equals(identity.rootCommitId(), repository.rootCommitId());
  }

  private static int userVersion(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(USER_VERSION);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new IllegalStateException("nenhuma linha de " + USER_VERSION);
      }
      return rows.getInt(1);
    }
  }

  private static List<String> quickCheckReport(Connection reader) throws SQLException {
    final List<String> report = new ArrayList<>();
    try (PreparedStatement statement = reader.prepareStatement(QUICK_CHECK);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        report.add(rows.getString(1));
      }
    }
    return List.copyOf(report);
  }

  private boolean closeConnections() {
    try {
      connections.close();
    } catch (SQLException | RuntimeException failure) {
      if (!connections.allClosed()) {
        keepLockUntilExit(lock, directory, failure);
        return false;
      }
      Log.warn("falha ao fechar as conexões do cache de " + directory + ": " + failure);
    }
    return true;
  }

  private static void keepLockUntilExit(CacheLock lock, Path directory, Throwable failure) {
    lock.keepUntilExit();
    Log.error(
        "uma conexão com o cache em "
            + directory
            + " ficou aberta; nada é renomeado e o lock dele segue preso até o analisador sair",
        failure);
  }

  private static void closeAfter(SqliteConnections connections, Throwable original) {
    try {
      connections.close();
    } catch (SQLException | RuntimeException cleanup) {
      original.addSuppressed(cleanup);
    }
  }

  private static Path realPathOf(Path repositoryRoot) throws CacheUnavailableException {
    try {
      return repositoryRoot.toRealPath();
    } catch (IOException failure) {
      throw new CacheUnavailableException(
          CacheUnavailableException.Kind.STORAGE,
          "não foi possível resolver o caminho real da raiz do repositório " + repositoryRoot,
          failure);
    }
  }

  private static void releaseAfter(CacheLock lock, Throwable original) {
    try {
      lock.close();
    } catch (IOException cleanup) {
      original.addSuppressed(cleanup);
    }
  }

  private record Recipe(
      RepositoryIdentity identity,
      String analyzerVersion,
      InstantSource clock,
      Migrations migrations) {

    Recipe {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(analyzerVersion, "analyzerVersion");
      Objects.requireNonNull(clock, "clock");
      Objects.requireNonNull(migrations, "migrations");
    }

    CacheMetadata freshMetadata(int schemaVersion) {
      final long now = clock.millis();
      return new CacheMetadata(
          identity.key().value(),
          schemaVersion,
          analyzerVersion,
          AnalysisConstants.PROTOCOL_VERSION,
          now,
          now);
    }
  }

  private record Attempt(SqliteConnections connections, boolean created) {}
}
