package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
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
import org.eclipse.jgit.lib.ConfigConstants;
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
  private static final String INDEX = "index";
  private static final String REFTABLE = "reftable";
  private static final String TABLES_LIST = "tables.list";
  private static final String INCLUDE_SECTION = "include";
  private static final String INCLUDE_KEY = "path";
  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private final int maxGitConfigBytes;
  private final int maxPackedRefsBytes;

  GitMetadataGuard(RepositoryLimits limits) {
    this.maxGitConfigBytes = limits.maxGitConfigBytes();
    this.maxPackedRefsBytes = limits.maxPackedRefsBytes();
  }

  boolean admits(Path root) {
    try {
      final Optional<Path> gitDir = locateGitDir(root);
      if (gitDir.isPresent()) {
        checkGitDir(gitDir.get(), root);
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

  static boolean namesNetworkLocation(String path) {
    return path.length() >= 2 && isSlash(path.charAt(0)) && isSlash(path.charAt(1));
  }

  private static boolean isSlash(char character) {
    return character == '/' || character == '\\';
  }

  private Optional<Path> locateGitDir(Path root) throws IOException, Refusal {
    final Path dotGit = dotGitOf(root);
    final Optional<BasicFileAttributes> dotGitAttributes = attributes(dotGit, root);
    final boolean dotGitIsDirectory =
        dotGitAttributes.map(BasicFileAttributes::isDirectory).orElse(false);
    if (dotGitIsDirectory && looksLikeGitDir(dotGit, root)) {
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
    return looksLikeGitDir(root, root) ? Optional.of(root) : Optional.empty();
  }

  private static Path dotGitOf(Path root) throws IOException, Refusal {
    final Path dotGit = root.resolve(Constants.DOT_GIT);
    final Optional<BasicFileAttributes> found = ownAttributes(dotGit);
    if (found.isEmpty() || !found.get().isSymbolicLink()) {
      return dotGit;
    }
    return resolveAgainst(root, Files.readSymbolicLink(dotGit).toString());
  }

  private boolean looksLikeGitDir(Path directory, Path root) throws IOException, Refusal {
    final Path commonDir = commonDirOf(directory, root);
    if (attributes(commonDir.resolve(Constants.OBJECTS), root).isEmpty()
        || attributes(commonDir.resolve(REFS), root).isEmpty()) {
      return false;
    }
    if (attributes(commonDir.resolve(REFTABLE), root).isPresent()) {
      return true;
    }
    return readPlainFileWithin(commonDir.resolve(Constants.HEAD), maxGitConfigBytes, root)
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
        readPlainFileWithin(dotGitFile, maxGitConfigBytes, root)
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
    if (!attributes(target, root).map(BasicFileAttributes::isDirectory).orElse(false)) {
      throw new Refusal(dotGitFile + " aponta para " + target + ", que não é um diretório");
    }
    return Optional.of(target);
  }

  private void checkGitDir(Path gitDir, Path root) throws IOException, Refusal {
    requireSmallPlainFile(gitDir.resolve(Constants.HEAD), root);
    requireSmallPlainFile(gitDir.resolve(CONFIG_WORKTREE), root);
    requireNoSymbolicLink(gitDir.resolve(INDEX), root);
    final Optional<byte[]> workTreePointer =
        readPlainFileWithin(gitDir.resolve(Constants.GITDIR_FILE), maxGitConfigBytes, root);
    if (workTreePointer.isPresent()) {
      checkWorkTreePath(
          gitDir, new String(workTreePointer.get(), StandardCharsets.UTF_8).trim(), "gitdir", root);
    }
    final Path commonDir = commonDirOf(gitDir, root);
    requireSmallPlainFile(commonDir.resolve(Constants.HEAD), root);
    final Path config = commonDir.resolve(Constants.CONFIG);
    if (requireSmallPlainFile(config, root)) {
      final Set<Path> visited = new HashSet<>();
      visited.add(config.toRealPath());
      checkConfig(config, new ConfigScope(gitDir, commonDir, root), 0, visited);
    }
    requirePlainFileWithin(commonDir.resolve(Constants.PACKED_REFS), maxPackedRefsBytes, root);
    requireSmallPlainFile(commonDir.resolve(Constants.SHALLOW), root);
    checkLooseRefChain(gitDir, commonDir, root);
    checkReftable(commonDir, root);
  }

  private Path commonDirOf(Path gitDir, Path root) throws IOException, Refusal {
    final Optional<byte[]> commondir =
        readPlainFileWithin(gitDir.resolve(COMMONDIR), maxGitConfigBytes, root);
    if (commondir.isEmpty()) {
      return gitDir;
    }
    final Path commonDir =
        resolveAgainst(gitDir, new String(commondir.get(), StandardCharsets.UTF_8).trim());
    requireNoSymbolicLink(commonDir, root);
    return commonDir;
  }

  private static void checkWorkTreePath(Path gitDir, String value, String source, Path root)
      throws IOException, Refusal {
    if (value.isEmpty()) {
      return;
    }
    if (namesNetworkLocation(value)) {
      throw new Refusal(source + " nomeia o local de rede " + value);
    }
    requireNoSymbolicLink(resolveAgainst(gitDir, value), root);
  }

  private void checkConfig(Path configFile, ConfigScope scope, int depth, Set<Path> visited)
      throws IOException, Refusal {
    final Config parsed = parse(configFile);
    for (final String workTree :
        parsed.getStringList(
            ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_WORKTREE)) {
      checkWorkTreePath(scope.gitDir(), workTree, configFile + " core.worktree", scope.root());
    }
    for (final String value : includePaths(parsed)) {
      final Path included = resolveInclude(value, scope.commonDir());
      final Optional<BasicFileAttributes> found = attributes(included, scope.root());
      if (found.isEmpty()) {
        continue;
      }
      if (depth >= MAX_INCLUDE_DEPTH) {
        throw new Refusal(
            configFile + " aninha includes com profundidade maior que " + MAX_INCLUDE_DEPTH);
      }
      requireWithin(included, found.get(), maxGitConfigBytes);
      if (!visited.add(included.toRealPath())) {
        throw new Refusal(configFile + " inclui " + included + " mais de uma vez");
      }
      checkConfig(included, scope, depth + 1, visited);
    }
  }

  private static Config parse(Path configFile) throws IOException, Refusal {
    final Config parsed = new Config();
    try {
      parsed.fromText(decodeConfig(Files.readAllBytes(configFile)));
    } catch (ConfigInvalidException invalid) {
      throw new Refusal(configFile + " não é uma config Git válida: " + invalid.getMessage());
    }
    return parsed;
  }

  private static List<String> includePaths(Config parsed) {
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

  private void checkLooseRefChain(Path gitDir, Path commonDir, Path root)
      throws IOException, Refusal {
    String name = Constants.HEAD;
    for (int depth = 0; depth <= RefDatabase.MAX_SYMBOLIC_REF_DEPTH; depth++) {
      final Optional<String> target =
          readPlainFileWithin(looseRefFile(name, gitDir, commonDir), maxGitConfigBytes, root)
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

  private void checkReftable(Path commonDir, Path root) throws IOException, Refusal {
    final Path reftable = commonDir.resolve(REFTABLE);
    if (!attributes(reftable, root).map(BasicFileAttributes::isDirectory).orElse(false)) {
      return;
    }
    final Path tablesList = reftable.resolve(TABLES_LIST);
    final Optional<byte[]> listed = readPlainFileWithin(tablesList, maxGitConfigBytes, root);
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
      requirePlainFile(reftable.resolve(table), root);
    }
  }

  private static Optional<byte[]> readPlainFileWithin(Path file, int maxBytes, Path root)
      throws IOException, Refusal {
    return requirePlainFileWithin(file, maxBytes, root)
        ? Optional.of(Files.readAllBytes(file))
        : Optional.empty();
  }

  private boolean requireSmallPlainFile(Path file, Path root) throws IOException, Refusal {
    return requirePlainFileWithin(file, maxGitConfigBytes, root);
  }

  private static boolean requirePlainFileWithin(Path file, int maxBytes, Path root)
      throws IOException, Refusal {
    final Optional<BasicFileAttributes> found = attributes(file, root);
    if (found.isEmpty()) {
      return false;
    }
    requireWithin(file, found.get(), maxBytes);
    return true;
  }

  private static void requireWithin(Path file, BasicFileAttributes attributes, int maxBytes)
      throws Refusal {
    requireRegular(file, attributes);
    if (attributes.size() > maxBytes) {
      throw new Refusal(
          file + " tem " + attributes.size() + " bytes, acima de " + maxBytes + " bytes");
    }
  }

  private static void requirePlainFile(Path file, Path root) throws IOException, Refusal {
    final Optional<BasicFileAttributes> found = attributes(file, root);
    if (found.isPresent()) {
      requireRegular(file, found.get());
    }
  }

  private static void requireRegular(Path file, BasicFileAttributes attributes) throws Refusal {
    if (!attributes.isRegularFile()) {
      throw new Refusal(file + " não é um arquivo comum");
    }
  }

  private static Optional<BasicFileAttributes> attributes(Path path, Path root)
      throws IOException, Refusal {
    Path walked = path.getRoot();
    Optional<BasicFileAttributes> found = walked == null ? Optional.empty() : ownAttributes(walked);
    for (final Path name : path) {
      walked = walked == null ? name : walked.resolve(name);
      found = ownAttributes(walked);
      if (found.isEmpty()) {
        return found;
      }
      if (found.get().isSymbolicLink() && walked.startsWith(root)) {
        throw new Refusal(
            walked + " é um link simbólico dentro do repositório, que o JGit seguiria");
      }
    }
    return found;
  }

  private static void requireNoSymbolicLink(Path path, Path root) throws IOException, Refusal {
    attributes(path, root);
  }

  private static Optional<BasicFileAttributes> ownAttributes(Path path) throws IOException {
    try {
      return Optional.of(
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    }
  }

  private static Path resolveAgainst(Path base, String value) throws Refusal {
    if (namesNetworkLocation(value)) {
      throw new Refusal("o repositório nomeia o local de rede " + value + " sob " + base);
    }
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

  private record ConfigScope(Path gitDir, Path commonDir, Path root) {}

  private static final class Refusal extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    Refusal(String message) {
      super(message, null, false, false);
    }
  }
}
