package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sqlite.SQLiteException;

public final class CacheRecovery {

  public enum Verdict {
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

  private static final String WAL_SUFFIX = "-wal";
  private static final String SHM_SUFFIX = "-shm";
  private static final String EPOCH_GROUP = "epoch";
  private static final String BACKUP_GROUP = "backup";

  private static final Pattern BACKUP =
      Pattern.compile("^analysis\\.sqlite\\.(?<epoch>\\d{13})\\.bak$");

  private static final Pattern BACKUP_SIDECAR =
      Pattern.compile("^(?<backup>analysis\\.sqlite\\.\\d{13}\\.bak)-(?:wal|shm)$");

  private static final Pattern QUARANTINE_NAME =
      Pattern.compile("^analysis\\.sqlite\\.(?<epoch>\\d{13})\\.bak(?:-wal|-shm)?$");

  private static final long SMALLEST_THIRTEEN_DIGIT_EPOCH = 1_000_000_000_000L;

  private CacheRecovery() {}

  public static boolean isCorruption(SQLException failure) {
    return classify(failure) == Verdict.CORRUPT;
  }

  public static Verdict classify(Throwable failure) {
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
    Objects.requireNonNull(databaseFile, "databaseFile");
    Objects.requireNonNull(clock, "clock");
    final long newest = newestEpoch(databaseFile.getParent());
    final long epoch =
        Math.max(Math.max(clock.millis(), newest + 1), SMALLEST_THIRTEEN_DIGIT_EPOCH);
    final Path backup = moveToFreeName(databaseFile, epoch);
    try {
      final Path wal = sibling(databaseFile, WAL_SUFFIX);
      if (walHasFrames(databaseFile)) {
        Files.move(wal, sibling(backup, WAL_SUFFIX));
      } else {
        Files.deleteIfExists(wal);
      }
      Files.deleteIfExists(sibling(databaseFile, SHM_SUFFIX));
    } catch (IOException failure) {
      throw new IncompleteQuarantineException(databaseFile, backup, failure);
    }
    return backup;
  }

  static List<Path> backups(Path cacheDirectory) throws IOException {
    final List<Backup> found = new ArrayList<>();
    for (final Path entry : entries(cacheDirectory)) {
      final OptionalLong epoch = epochOf(BACKUP, entry);
      if (epoch.isPresent() && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
        found.add(new Backup(entry, epoch.getAsLong()));
      }
    }
    return found.stream()
        .sorted(Comparator.comparingLong(Backup::epoch).reversed())
        .map(Backup::path)
        .toList();
  }

  static void retain(Path cacheDirectory, int keep) {
    retainSparing(cacheDirectory, keep, Set.of());
  }

  static void retain(Path cacheDirectory, int keep, Path justMade) {
    Objects.requireNonNull(justMade, "justMade");
    retainSparing(cacheDirectory, keep, Set.of(justMade.getFileName()));
  }

  private static void retainSparing(Path cacheDirectory, int keep, Set<Path> sparedNames) {
    if (keep < 0) {
      throw new IllegalArgumentException(
          "não é possível manter um número negativo de backups: " + keep);
    }
    final List<Path> backups;
    try {
      backups = backups(cacheDirectory);
    } catch (IOException unlistable) {
      Log.warn(
          "a retenção de backups do cache não conseguiu listar "
              + cacheDirectory
              + ": "
              + unlistable);
      return;
    }
    for (final Path stale : backups.subList(Math.min(keep, backups.size()), backups.size())) {
      if (sparedNames.contains(stale.getFileName())) {
        continue;
      }
      if (deleteRegularFile(stale)) {
        deleteRegularFile(sibling(stale, WAL_SUFFIX));
        deleteRegularFile(sibling(stale, SHM_SUFFIX));
      }
    }
    deleteOrphanSidecars(cacheDirectory);
  }

  static boolean walHasFrames(Path databaseFile) throws IOException {
    try {
      return Files.size(sibling(databaseFile, WAL_SUFFIX)) > 0;
    } catch (NoSuchFileException absent) {
      return false;
    }
  }

  private static Path moveToFreeName(Path databaseFile, long firstEpoch) throws IOException {
    long epoch = firstEpoch;
    while (true) {
      final Path backup = databaseFile.resolveSibling(backupName(epoch));
      try {
        Files.move(databaseFile, backup);
        return backup;
      } catch (FileAlreadyExistsException taken) {
        epoch++;
      }
    }
  }

  private static String backupName(long epoch) {
    return RepositoryCache.DATABASE_FILE + "." + epoch + ".bak";
  }

  private static long newestEpoch(Path cacheDirectory) throws IOException {
    long newest = 0;
    for (final Path entry : entries(cacheDirectory)) {
      newest = Math.max(newest, epochOf(QUARANTINE_NAME, entry).orElse(0));
    }
    return newest;
  }

  private static OptionalLong epochOf(Pattern pattern, Path entry) {
    final Matcher name = pattern.matcher(entry.getFileName().toString());
    if (!name.matches()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Long.parseLong(name.group(EPOCH_GROUP)));
  }

  private static void deleteOrphanSidecars(Path cacheDirectory) {
    final List<Path> orphans = new ArrayList<>();
    try {
      for (final Path entry : entries(cacheDirectory)) {
        final Matcher sidecar = BACKUP_SIDECAR.matcher(entry.getFileName().toString());
        if (sidecar.matches()
            && !Files.isRegularFile(
                entry.resolveSibling(sidecar.group(BACKUP_GROUP)), LinkOption.NOFOLLOW_LINKS)) {
          orphans.add(entry);
        }
      }
    } catch (IOException unlistable) {
      Log.warn(
          "a retenção de backups do cache não conseguiu listar "
              + cacheDirectory
              + ": "
              + unlistable);
      return;
    }
    for (final Path orphan : orphans) {
      deleteRegularFile(orphan);
    }
  }

  private static boolean deleteRegularFile(Path file) {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
    }
    try {
      Files.deleteIfExists(file);
      Log.info("a retenção de backups do cache apagou " + file);
      return true;
    } catch (IOException failure) {
      Log.warn(
          "a retenção de backups do cache não conseguiu apagar "
              + file
              + "; a próxima reconstrução tenta de novo: "
              + failure);
      return false;
    }
  }

  private static List<Path> entries(Path directory) throws IOException {
    final List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (final Path entry : stream) {
        entries.add(entry);
      }
    } catch (DirectoryIteratorException failure) {
      throw failure.getCause();
    }
    return entries;
  }

  private static Path sibling(Path file, String suffix) {
    return file.resolveSibling(file.getFileName() + suffix);
  }

  private record Backup(Path path, long epoch) {}

  static final class IncompleteQuarantineException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path backup;

    IncompleteQuarantineException(Path databaseFile, Path backup, IOException cause) {
      super(
          "moveu "
              + databaseFile
              + " para "
              + backup
              + " mas não conseguiu mover os arquivos auxiliares dele",
          cause);
      this.backup = backup;
    }

    Path backup() {
      return backup;
    }
  }
}
