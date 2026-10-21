package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.repository.GitMetadataGuard.Refusal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;

final class GitObjectStoreGuard {

  private static final String INFO = "info";
  private static final String PACK = "pack";
  private static final String PRESERVED = "preserved";
  private static final String GRAFTS = "grafts";
  private static final String ALTERNATES = "alternates";
  private static final String COMMIT_GRAPH = "commit-graph";
  private static final String MULTI_PACK_INDEX = "multi-pack-index";
  private static final String PACK_INDEX_SUFFIX = ".idx";

  private static final Pattern LOOSE_FANOUT = Pattern.compile("[0-9a-f]{2}");

  private final Path root;
  private final RepositoryLimits limits;
  private final GitMetadataGuard metadata;

  private final Set<Path> visited = new HashSet<>();

  private int alternates;
  private int looseObjects;
  private long packIndexBytes;

  private GitObjectStoreGuard(Path root, RepositoryLimits limits, GitMetadataGuard metadata) {
    this.root = root;
    this.limits = limits;
    this.metadata = metadata;
  }

  static void check(Path commonDir, Path root, RepositoryLimits limits, GitMetadataGuard metadata)
      throws IOException, Refusal {
    new GitObjectStoreGuard(
            Objects.requireNonNull(root, "root"),
            Objects.requireNonNull(limits, "limits"),
            Objects.requireNonNull(metadata, "metadata"))
        .checkRepository(Objects.requireNonNull(commonDir, "commonDir"), 0);
  }

  private void checkRepository(Path commonDir, int depth) throws IOException, Refusal {
    requireFileWithin(commonDir.resolve(INFO).resolve(GRAFTS), limits.maxGitConfigBytes());
    checkObjectDirectory(commonDir.resolve(Constants.OBJECTS), depth);
  }

  private void checkObjectDirectory(Path objects, int depth) throws IOException, Refusal {
    final Optional<Reached> reached = reach(objects);
    if (reached.isEmpty() || !reached.get().attributes().isDirectory()) {
      return;
    }
    final Path real = reached.get().path();
    if (!visited.add(real)) {
      return;
    }
    checkLooseObjects(real);
    checkPacks(real.resolve(PACK));
    requireFileWithin(real.resolve(INFO).resolve(COMMIT_GRAPH), limits.maxCommitGraphBytes());
    checkAlternates(real, depth);
  }

  private void checkLooseObjects(Path objects) throws IOException, Refusal {
    for (final Path fanout : entries(objects)) {
      if (!LOOSE_FANOUT.matcher(fanout.getFileName().toString()).matches()) {
        continue;
      }
      final Optional<Reached> directory = reachEntry(fanout);
      if (directory.isEmpty() || !directory.get().attributes().isDirectory()) {
        continue;
      }
      for (final Path object : entries(directory.get().path())) {
        looseObjects++;
        if (looseObjects > limits.maxLooseObjects()) {
          throw new Refusal(
              objects
                  + " leva os objetos soltos do repositório e dos seus alternates para mais de "
                  + limits.maxLooseObjects());
        }
        requireRegular(object, reachEntry(object));
      }
    }
  }

  private void checkPacks(Path pack) throws IOException, Refusal {
    final Optional<Reached> directory = reachEntry(pack);
    if (directory.isEmpty()) {
      return;
    }
    if (!directory.get().attributes().isDirectory()) {
      throw new Refusal(pack + " não é um diretório");
    }
    for (final Path entry : entries(directory.get().path())) {
      final String name = entry.getFileName().toString();
      final Optional<Reached> reached = reachEntry(entry);
      if (PRESERVED.equals(name)) {
        if (reached.isPresent() && !isEmptyDirectory(reached.get())) {
          throw new Refusal(
              entry + " tem packs preservados, que o JGit liga de volta ao repositório");
        }
        continue;
      }
      requireRegular(entry, reached);
      if (reached.isPresent()
          && (name.endsWith(PACK_INDEX_SUFFIX) || MULTI_PACK_INDEX.equals(name))) {
        countPackIndex(entry, reached.get().attributes().size());
      }
    }
  }

  private void countPackIndex(Path index, long size) throws Refusal {
    if (size > limits.maxPackIndexBytes()) {
      throw new Refusal(index + " tem " + size + " bytes, acima de " + limits.maxPackIndexBytes());
    }
    packIndexBytes += size;
    if (packIndexBytes > limits.maxPackIndexTotalBytes()) {
      throw new Refusal(
          index
              + " leva os índices de pack do repositório e dos seus alternates para mais de "
              + limits.maxPackIndexTotalBytes()
              + " bytes");
    }
  }

  private void checkAlternates(Path objects, int depth) throws IOException, Refusal {
    final Path file = objects.resolve(INFO).resolve(ALTERNATES);
    final Optional<Reached> found = requireFileWithin(file, limits.maxGitConfigBytes());
    if (found.isEmpty()) {
      return;
    }
    final byte[] text = Files.readAllBytes(found.get().path());
    final List<String> lines = new String(text, StandardCharsets.UTF_8).lines().toList();
    for (final String line : lines) {
      alternates++;
      if (alternates > limits.maxGitAlternates()) {
        throw new Refusal(
            file + " leva os alternates do repositório para mais de " + limits.maxGitAlternates());
      }
      if (depth + 1 > limits.maxGitAlternatesDepth()) {
        throw new Refusal(
            file
                + " aninha alternates com profundidade maior que "
                + limits.maxGitAlternatesDepth());
      }
      if (GitMetadataGuard.namesNetworkLocation(line)) {
        throw new Refusal(file + " nomeia o local de rede " + line);
      }
      checkAlternate(file, GitMetadataGuard.resolveAgainst(objects, line), depth + 1);
    }
  }

  private void checkAlternate(Path file, Path alternate, int depth) throws IOException, Refusal {
    final Path parent = alternate.getParent();
    final Optional<Reached> repository = parent == null ? Optional.empty() : reach(parent);
    if (repository.isPresent() && repository.get().attributes().isDirectory()) {
      final Path gitDir = repository.get().path();
      final Optional<Path> commonDir = alternateRepository(file, gitDir);
      if (commonDir.isPresent()) {
        checkRepository(commonDir.get(), depth);
        return;
      }
    }
    checkObjectDirectory(alternate, depth);
  }

  private Optional<Path> alternateRepository(Path file, Path gitDir) throws IOException, Refusal {
    try {
      if (!metadata.looksLikeGitDir(gitDir, root)) {
        return Optional.empty();
      }
      return Optional.of(metadata.checkMetadata(gitDir, root));
    } catch (Refusal refused) {
      throw new Refusal(
          file + " abre o repositório " + gitDir + ", o que é recusado: " + refused.getMessage());
    }
  }

  private Optional<Reached> requireFileWithin(Path file, long maxBytes)
      throws IOException, Refusal {
    final Optional<Reached> reached = reach(file);
    requireRegular(file, reached);
    if (reached.isPresent() && reached.get().attributes().size() > maxBytes) {
      throw new Refusal(
          file + " tem " + reached.get().attributes().size() + " bytes, acima de " + maxBytes);
    }
    return reached;
  }

  private static void requireRegular(Path entry, Optional<Reached> reached) throws Refusal {
    if (reached.isPresent() && !reached.get().attributes().isRegularFile()) {
      throw new Refusal(entry + " não é um arquivo comum");
    }
  }

  private static boolean isEmptyDirectory(Reached reached) throws IOException {
    if (!reached.attributes().isDirectory()) {
      return false;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(reached.path())) {
      return !entries.iterator().hasNext();
    } catch (DirectoryIteratorException failed) {
      throw failed.getCause();
    }
  }

  private static List<Path> entries(Path directory) throws IOException {
    try (DirectoryStream<Path> listed = Files.newDirectoryStream(directory)) {
      final List<Path> entries = new ArrayList<>();
      for (final Path entry : listed) {
        entries.add(entry);
      }
      return entries;
    } catch (DirectoryIteratorException failed) {
      throw failed.getCause();
    }
  }

  private Optional<Reached> reach(Path path) throws IOException, Refusal {
    Path walked = path.getRoot();
    Optional<Reached> reached = walked == null ? Optional.empty() : reachEntry(walked);
    final int names = path.getNameCount();
    for (int index = 0; index < names; index++) {
      final Path name = path.getName(index);
      walked = (walked == null ? name : walked.resolve(name)).normalize();
      reached = reachEntry(walked);
      if (reached.isEmpty()) {
        return reached;
      }
      walked = reached.get().path();
      if (index < names - 1 && !reached.get().attributes().isDirectory()) {
        return Optional.empty();
      }
    }
    return reached;
  }

  private Optional<Reached> reachEntry(Path entry) throws IOException, Refusal {
    final Optional<BasicFileAttributes> own = GitMetadataGuard.ownAttributes(entry);
    if (own.isEmpty() || !own.get().isSymbolicLink()) {
      return own.map(attributes -> new Reached(entry, attributes));
    }
    if (entry.startsWith(root)) {
      throw new Refusal(entry + " é um link simbólico dentro do repositório, que o JGit seguiria");
    }
    final Path real;
    try {
      real = entry.toRealPath();
    } catch (NoSuchFileException dangling) {
      return Optional.empty();
    }
    return Optional.of(
        new Reached(
            real,
            Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
  }

  private record Reached(Path path, BasicFileAttributes attributes) {}
}
