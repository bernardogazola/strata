package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.SystemReader;

final class GitMetadataGuard {

  private static final int MAX_INCLUDE_DEPTH = 10;

  private static final int LOOSE_REF_READ_LIMIT = 4096;

  private static final String GITDIR_PREFIX = "gitdir: ";
  private static final String SYMREF_PREFIX = "ref: ";
  private static final String HEAD_REF_PREFIX = "ref: refs/";
  private static final String HOME_PREFIX = "~/";
  private static final String REFS = "refs";
  private static final String COMMONDIR = "commondir";
  private static final String CONFIG_WORKTREE = "config.worktree";
  private static final String REFTABLE = "reftable";
  private static final String TABLES_LIST = "tables.list";
  private static final String INCLUDE_SECTION = "include";
  private static final String INCLUDE_KEY = "path";
  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private final int maxGitConfigBytes;

  GitMetadataGuard(RepositoryLimits limits) {
    this.maxGitConfigBytes = limits.maxGitConfigBytes();
  }

  boolean admits(Path root) {
    try {
      final Optional<Path> gitDir = locateGitDir(root);
      if (gitDir.isPresent()) {
        checkGitDir(gitDir.get());
      }
      return true;
    } catch (Refusal refusal) {
      Log.warn("recusando os metadados git sob " + root + ": " + refusal.getMessage());
      return false;
    } catch (IOException unreadable) {
      Log.error(
          "recusando os metadados git sob " + root + ": não foi possível inspecioná-los",
          unreadable);
      return false;
    }
  }

  private Optional<Path> locateGitDir(Path root) throws IOException, Refusal {
    final Path dotGit = root.resolve(Constants.DOT_GIT);
    final Optional<BasicFileAttributes> dotGitAttributes = attributes(dotGit);
    final boolean dotGitIsDirectory =
        dotGitAttributes.map(BasicFileAttributes::isDirectory).orElse(false);
    if (dotGitIsDirectory && looksLikeGitDir(dotGit)) {
      return Optional.of(dotGit);
    }
    final boolean dotGitIsFile =
        dotGitAttributes.map(BasicFileAttributes::isRegularFile).orElse(false);
    if (dotGitIsFile) {
      final Optional<Path> target = gitdirTarget(root, dotGit);
      if (target.isPresent()) {
        return target;
      }
    }
    return looksLikeGitDir(root) ? Optional.of(root) : Optional.empty();
  }

  private boolean looksLikeGitDir(Path directory) throws IOException, Refusal {
    final Path commonDir = commonDirOf(directory);
    if (!Files.exists(commonDir.resolve(Constants.OBJECTS))
        || !Files.exists(commonDir.resolve(REFS))) {
      return false;
    }
    if (Files.exists(commonDir.resolve(REFTABLE))) {
      return true;
    }
    return readSmallPlainFile(commonDir.resolve(Constants.HEAD))
        .map(GitMetadataGuard::isValidHead)
        .orElse(false);
  }

  private static boolean isValidHead(byte[] content) {
    if (content.length == 0 || content.length > LOOSE_REF_READ_LIMIT) {
      return false;
    }
    int end = content.length;
    if (content[end - 1] == '\n') {
      end--;
    }
    final String line = RawParseUtils.decode(content, 0, end);
    return line.startsWith(HEAD_REF_PREFIX) || ObjectId.isId(line);
  }

  private Optional<Path> gitdirTarget(Path root, Path dotGitFile) throws IOException, Refusal {
    final byte[] content =
        readSmallPlainFile(dotGitFile)
            .orElseThrow(() -> new Refusal(dotGitFile + " sumiu durante a inspeção"));
    if (content.length <= GITDIR_PREFIX.length() || !startsWith(content, GITDIR_PREFIX)) {
      return Optional.empty();
    }
    final int pathStart = GITDIR_PREFIX.length();
    int lineEnd = RawParseUtils.nextLF(content, pathStart);
    final boolean windows = SystemReader.getInstance().isWindows();
    while (content[lineEnd - 1] == '\n' || (content[lineEnd - 1] == '\r' && windows)) {
      lineEnd--;
    }
    if (lineEnd == pathStart) {
      return Optional.empty();
    }
    final Path target = resolveAgainst(root, RawParseUtils.decode(content, pathStart, lineEnd));
    if (!Files.isDirectory(target)) {
      throw new Refusal(dotGitFile + " aponta para " + target + ", que não é um diretório");
    }
    return Optional.of(target);
  }

  private void checkGitDir(Path gitDir) throws IOException, Refusal {
    requireSmallPlainFile(gitDir.resolve(Constants.HEAD));
    requireSmallPlainFile(gitDir.resolve(CONFIG_WORKTREE));
    final Path commonDir = commonDirOf(gitDir);
    requireSmallPlainFile(commonDir.resolve(Constants.HEAD));
    final Path config = commonDir.resolve(Constants.CONFIG);
    if (requireSmallPlainFile(config)) {
      final Set<Path> visited = new HashSet<>();
      visited.add(config.toRealPath());
      checkIncludes(config, commonDir, 0, visited);
    }
    requirePlainFile(commonDir.resolve(Constants.PACKED_REFS));
    requirePlainFile(commonDir.resolve(Constants.SHALLOW));
    checkLooseRefChain(gitDir, commonDir);
    checkReftable(commonDir);
  }

  private Path commonDirOf(Path gitDir) throws IOException, Refusal {
    final Optional<byte[]> commondir = readSmallPlainFile(gitDir.resolve(COMMONDIR));
    if (commondir.isEmpty()) {
      return gitDir;
    }
    return resolveAgainst(gitDir, new String(commondir.get(), StandardCharsets.UTF_8).trim());
  }

  private void checkIncludes(Path configFile, Path baseDirectory, int depth, Set<Path> visited)
      throws IOException, Refusal {
    for (final String value : includePaths(configFile)) {
      final Path included = resolveInclude(value, baseDirectory);
      if (!Files.exists(included)) {
        continue;
      }
      if (depth >= MAX_INCLUDE_DEPTH) {
        throw new Refusal(
            configFile + " aninha includes com profundidade maior que " + MAX_INCLUDE_DEPTH);
      }
      requireSmallPlainFile(included);
      if (!visited.add(included.toRealPath())) {
        throw new Refusal(configFile + " inclui " + included + " mais de uma vez");
      }
      checkIncludes(included, baseDirectory, depth + 1, visited);
    }
  }

  private static List<String> includePaths(Path configFile) throws IOException, Refusal {
    final Config parsed = new Config();
    try {
      parsed.fromText(decodeConfig(Files.readAllBytes(configFile)));
    } catch (ConfigInvalidException invalid) {
      throw new Refusal(configFile + " não é uma config Git válida: " + invalid.getMessage());
    }
    final List<String> paths =
        new ArrayList<>(Arrays.asList(parsed.getStringList(INCLUDE_SECTION, null, INCLUDE_KEY)));
    for (final String subsection : parsed.getSubsections(INCLUDE_SECTION)) {
      paths.addAll(Arrays.asList(parsed.getStringList(INCLUDE_SECTION, subsection, INCLUDE_KEY)));
    }
    return paths;
  }

  private static String decodeConfig(byte[] bytes) {
    if (startsWith(bytes, UTF8_BOM)) {
      return RawParseUtils.decode(StandardCharsets.UTF_8, bytes, UTF8_BOM.length, bytes.length);
    }
    return RawParseUtils.decode(bytes);
  }

  private static Path resolveInclude(String value, Path baseDirectory) throws Refusal {
    if (!value.startsWith(HOME_PREFIX)) {
      return resolveAgainst(baseDirectory, value);
    }
    final File home = FS.DETECTED.userHome();
    if (home == null) {
      throw new Refusal("o include " + value + " pede um diretório home, e não há nenhum");
    }
    return resolveAgainst(home.toPath(), value.substring(HOME_PREFIX.length()));
  }

  private void checkLooseRefChain(Path gitDir, Path commonDir) throws IOException, Refusal {
    String name = Constants.HEAD;
    for (int depth = 0; depth <= RefDatabase.MAX_SYMBOLIC_REF_DEPTH; depth++) {
      final Optional<String> target =
          readSmallPlainFile(looseRefFile(name, gitDir, commonDir))
              .flatMap(GitMetadataGuard::symbolicTarget);
      if (target.isEmpty()) {
        return;
      }
      if (!Repository.isValidRefName(target.get())) {
        throw new Refusal(name + " aponta para um nome de ref inválido: " + target.get());
      }
      name = target.get();
    }
  }

  private static Path looseRefFile(String name, Path gitDir, Path commonDir) {
    if (name.startsWith(Constants.R_REFS)) {
      return commonDir.resolve(Constants.R_REFS).resolve(name.substring(Constants.R_REFS.length()));
    }
    return name.equals(Constants.HEAD) ? gitDir.resolve(name) : commonDir.resolve(name);
  }

  private static Optional<String> symbolicTarget(byte[] content) {
    int length = Math.min(content.length, LOOSE_REF_READ_LIMIT);
    if (length <= SYMREF_PREFIX.length() || !startsWith(content, SYMREF_PREFIX)) {
      return Optional.empty();
    }
    if (length == LOOSE_REF_READ_LIMIT) {
      return Optional.empty();
    }
    while (length > 0 && Character.isWhitespace(content[length - 1])) {
      length--;
    }
    if (length <= SYMREF_PREFIX.length()) {
      return Optional.empty();
    }
    return Optional.of(RawParseUtils.decode(content, SYMREF_PREFIX.length(), length));
  }

  private void checkReftable(Path commonDir) throws IOException, Refusal {
    final Path reftable = commonDir.resolve(REFTABLE);
    if (!Files.isDirectory(reftable)) {
      return;
    }
    final Path tablesList = reftable.resolve(TABLES_LIST);
    final Optional<byte[]> listed = readSmallPlainFile(tablesList);
    if (listed.isEmpty()) {
      return;
    }
    for (final String table : new String(listed.get(), StandardCharsets.UTF_8).split("\\R")) {
      if (table.isEmpty()) {
        continue;
      }
      if (table.contains("/") || table.contains("\\") || ".".equals(table) || "..".equals(table)) {
        throw new Refusal(tablesList + " nomeia uma tabela fora do diretório reftable: " + table);
      }
      requirePlainFile(reftable.resolve(table));
    }
  }

  private Optional<byte[]> readSmallPlainFile(Path file) throws IOException, Refusal {
    return requireSmallPlainFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
  }

  private boolean requireSmallPlainFile(Path file) throws IOException, Refusal {
    final Optional<BasicFileAttributes> found = attributes(file);
    if (found.isEmpty()) {
      return false;
    }
    requireRegular(file, found.get());
    if (found.get().size() > maxGitConfigBytes) {
      throw new Refusal(
          file + " tem " + found.get().size() + " bytes, acima de " + maxGitConfigBytes + " bytes");
    }
    return true;
  }

  private static void requirePlainFile(Path file) throws IOException, Refusal {
    final Optional<BasicFileAttributes> found = attributes(file);
    if (found.isPresent()) {
      requireRegular(file, found.get());
    }
  }

  private static void requireRegular(Path file, BasicFileAttributes attributes) throws Refusal {
    if (!attributes.isRegularFile()) {
      throw new Refusal(file + " não é um arquivo comum");
    }
  }

  private static Optional<BasicFileAttributes> attributes(Path file) throws IOException {
    try {
      return Optional.of(Files.readAttributes(file, BasicFileAttributes.class));
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    }
  }

  private static Path resolveAgainst(Path base, String value) throws Refusal {
    try {
      final Path path = Path.of(value);
      return path.isAbsolute() ? path : base.resolve(path);
    } catch (InvalidPathException invalid) {
      throw new Refusal("não é um caminho utilizável sob " + base + ": " + invalid.getMessage());
    }
  }

  private static boolean startsWith(byte[] content, String prefix) {
    return startsWith(content, prefix.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    return content.length >= prefix.length
        && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static final class Refusal extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    Refusal(String message) {
      super(message, null, false, false);
    }
  }
}
