package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

public final class PersistenceRoot {

  public static final String DIRECTORY_NAME = "repositories";

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rwx------");

  private static final String POSIX_VIEW = "posix";

  private static final char JDBC_URL_QUERY = '?';

  private final Path directory;

  private PersistenceRoot(Path directory) {
    this.directory = directory;
  }

  public static PersistenceRoot prepare(Path dataDir) throws IOException {
    Objects.requireNonNull(dataDir, "dataDir");
    final Path directory = dataDir.resolve(DIRECTORY_NAME);
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(dataDir);
    }
    final Path real = ownerOnlyDirectory(directory).toRealPath();
    if (real.toString().indexOf(JDBC_URL_QUERY) >= 0) {
      throw new IOException(
          "o diretório do cache "
              + real
              + " contém '"
              + JDBC_URL_QUERY
              + "', que a URL JDBC do SQLite leria como o início dos seus pragmas");
    }
    Log.info("diretório do cache: " + real);
    return new PersistenceRoot(real);
  }

  public Path directory() {
    return directory;
  }

  Path cacheDirectory(RepositoryKey key) throws IOException {
    Objects.requireNonNull(key, "key");
    return ownerOnlyDirectory(directory.resolve(key.value()));
  }

  public boolean isInside(Path repositoryRoot) {
    Objects.requireNonNull(repositoryRoot, "repositoryRoot");
    return directory.startsWith(repositoryRoot);
  }

  private static Path ownerOnlyDirectory(Path directory) throws IOException {
    try {
      Files.createDirectory(directory, ownerOnly(directory));
    } catch (FileAlreadyExistsException present) {
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          directory + " não é um diretório de verdade: há um link ou um arquivo no lugar");
    }
    return directory;
  }

  private static FileAttribute<?>[] ownerOnly(Path directory) {
    if (directory.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW)) {
      return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)};
    }
    return new FileAttribute<?>[0];
  }
}
