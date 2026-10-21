package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class RepositoryWalker {

  private static final String JAVA_SUFFIX = ".java";

  private static final BooleanSupplier NEVER_CANCELLED = () -> false;

  private static final IntConsumer IGNORE_JAVA_FILES_SEEN = count -> {};

  private final RepositoryLimits limits;

  RepositoryWalker(RepositoryLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  RepositoryOutcome<RepositoryInventory> walk(Path root) {
    return walk(root, NEVER_CANCELLED, IGNORE_JAVA_FILES_SEEN);
  }

  RepositoryOutcome<RepositoryInventory> walk(
      Path root, BooleanSupplier cancelled, IntConsumer javaFilesSeen) {
    final Visitor visitor =
        new Visitor(
            root,
            limits,
            Objects.requireNonNull(cancelled, "cancelled"),
            Objects.requireNonNull(javaFilesSeen, "javaFilesSeen"));
    try {
      Files.walkFileTree(
          root, EnumSet.noneOf(FileVisitOption.class), limits.maxDirectoryDepth(), visitor);
    } catch (IOException impossible) {
      Log.error("a varredura de " + root + " falhou fora do visitor", impossible);
      return new RepositoryOutcome.Rejected<>(RepositoryIssue.rootUnreadable());
    }
    if (visitor.cancelledAtEntry > 0) {
      throw new CancellationException(
          "varredura do repositório cancelada na entrada " + visitor.cancelledAtEntry);
    }
    return visitor.outcome();
  }

  private static final class Visitor implements FileVisitor<Path> {

    private final Path root;
    private final RepositoryLimits limits;
    private final BooleanSupplier cancelled;
    private final IntConsumer javaFilesSeen;
    private final List<JavaSource> javaSources = new ArrayList<>();
    private final List<BuildFileEntry> buildFiles = new ArrayList<>();
    private final List<RepositoryIssue> warnings = new ArrayList<>();

    private int enumeratedEntries;

    private int totalFileCount;

    private int droppedWarnings;
    private int loggedEntries;
    private int silencedEntries;
    private RepositoryIssue refusal;

    private int cancelledAtEntry;

    Visitor(
        Path root, RepositoryLimits limits, BooleanSupplier cancelled, IntConsumer javaFilesSeen) {
      this.root = root;
      this.limits = limits;
      this.cancelled = cancelled;
      this.javaFilesSeen = javaFilesSeen;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
      if (directory.equals(root)) {
        return FileVisitResult.CONTINUE;
      }
      if (isGitMetadata(directory)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (!enumerate()) {
        return FileVisitResult.TERMINATE;
      }
      final String relative = relative(directory);
      if (tooLong(relative)) {
        warn(RepositoryIssue.unreadable(relative));
        return FileVisitResult.SKIP_SUBTREE;
      }
      recordBuildFile(directory, relative, false, 0);
      if (attributes.isOther() || attributes.isSymbolicLink()) {
        // O NIO desce em junções do Windows mesmo sem FOLLOW_LINKS, sem detectar laço.
        // Aqui elas contam como link e não são percorridas.
        totalFileCount++;
        checkEscape(directory, relative, attributes);
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
      if (isGitMetadata(file)) {
        return FileVisitResult.CONTINUE;
      }
      if (!enumerate()) {
        return FileVisitResult.TERMINATE;
      }
      final String relative = relative(file);
      if (attributes.isDirectory()) {
        warn(RepositoryIssue.unreadable(relative));
        recordBuildFile(file, relative, false, 0);
        return FileVisitResult.CONTINUE;
      }
      totalFileCount++;
      if (tooLong(relative)) {
        warn(RepositoryIssue.unreadable(relative));
        return FileVisitResult.CONTINUE;
      }
      if (attributes.isSymbolicLink()) {
        checkEscape(file, relative, attributes);
      }
      final String name = file.getFileName().toString();
      if (attributes.isRegularFile() && name.endsWith(JAVA_SUFFIX)) {
        javaSources.add(
            new JavaSource(relative, attributes.size(), attributes.lastModifiedTime().toMillis()));
      }
      recordBuildFile(file, relative, attributes.isRegularFile(), attributes.size());
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) {
      if (file.equals(root)) {
        Log.error("não foi possível listar a raiz do repositório " + root, failure);
        refusal = RepositoryIssue.rootUnreadable();
        return FileVisitResult.TERMINATE;
      }
      if (isGitMetadata(file)) {
        logEntry("ignorando a entrada ilegível " + file + ": " + failure);
        return FileVisitResult.CONTINUE;
      }
      if (!enumerate()) {
        return FileVisitResult.TERMINATE;
      }
      logEntry("ignorando a entrada ilegível " + file + ": " + failure);
      warn(RepositoryIssue.unreadable(relative(file)));
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
      if (failure == null) {
        return FileVisitResult.CONTINUE;
      }
      if (directory.equals(root)) {
        Log.error("a listagem da raiz do repositório " + root + " falhou no meio", failure);
        refusal = RepositoryIssue.rootUnreadable();
        return FileVisitResult.TERMINATE;
      }
      logEntry("a listagem de " + directory + " falhou no meio: " + failure);
      warn(RepositoryIssue.unreadable(relative(directory)));
      return FileVisitResult.CONTINUE;
    }

    private boolean enumerate() {
      enumeratedEntries++;
      if (enumeratedEntries > limits.maxEnumeratedFiles()) {
        refusal = RepositoryIssue.tooManyFiles(limits.maxEnumeratedFiles(), enumeratedEntries);
        return false;
      }
      if (enumeratedEntries % AnalysisConstants.WALK_CHECK_STRIDE_ENTRIES != 0) {
        return true;
      }
      javaFilesSeen.accept(javaSources.size());
      if (cancelled.getAsBoolean()) {
        cancelledAtEntry = enumeratedEntries;
        return false;
      }
      return true;
    }

    private void recordBuildFile(Path entry, String relative, boolean regularFile, long size) {
      if (BuildSystemDetector.BUILD_FILE_NAMES.contains(entry.getFileName().toString())) {
        buildFiles.add(new BuildFileEntry(relative, regularFile, size));
      }
    }

    private void checkEscape(Path link, String relative, BasicFileAttributes attributes) {
      final Path text;
      try {
        text = Files.readSymbolicLink(link);
      } catch (NotLinkException notALink) {
        if (attributes.isOther()) {
          checkJunction(link, relative);
        } else {
          logEntry("a entrada " + link + " deixou de ser um link simbólico durante a varredura");
        }
        return;
      } catch (IOException unreadable) {
        logEntry("não foi possível ler o link simbólico " + link + ": " + unreadable);
        warn(RepositoryIssue.unreadable(relative));
        return;
      }
      if (escapes(link, text)) {
        logEntry(
            "o link simbólico " + link + " aponta para fora da raiz do repositório, para " + text);
        warn(RepositoryIssue.pathEscapesRoot(relative));
      }
    }

    private boolean escapes(Path link, Path text) {
      if (GitMetadataGuard.namesNetworkLocation(text.toString())) {
        return true;
      }
      final Path target = text.isAbsolute() ? text : link.getParent().resolve(text);
      return !target.normalize().startsWith(root);
    }

    private void checkJunction(Path junction, String relative) {
      final Path target;
      try {
        target = junction.toRealPath();
      } catch (IOException dangling) {
        logEntry("a junction " + junction + " não resolve: " + dangling);
        return;
      }
      if (!target.startsWith(root)) {
        logEntry("a junction " + junction + " escapa da raiz do repositório para " + target);
        warn(RepositoryIssue.pathEscapesRoot(relative));
      }
    }

    private void logEntry(String message) {
      if (loggedEntries < limits.maxWarnings()) {
        loggedEntries++;
        Log.warn(message);
      } else {
        silencedEntries++;
      }
    }

    private void warn(RepositoryIssue issue) {
      if (warnings.size() < limits.maxWarnings()) {
        warnings.add(issue);
      } else {
        droppedWarnings++;
      }
    }

    private boolean tooLong(String relative) {
      return relative.getBytes(StandardCharsets.UTF_8).length > limits.maxPathBytes();
    }

    private static boolean isGitMetadata(Path entry) {
      final Path name = entry.getFileName();
      return name != null && ".git".equals(name.toString());
    }

    private String relative(Path entry) {
      final StringBuilder joined = new StringBuilder();
      for (final Path name : root.relativize(entry)) {
        if (!joined.isEmpty()) {
          joined.append('/');
        }
        joined.append(name);
      }
      return joined.toString();
    }

    RepositoryOutcome<RepositoryInventory> outcome() {
      if (refusal != null) {
        return new RepositoryOutcome.Rejected<>(refusal);
      }
      if (droppedWarnings > 0) {
        Log.warn(
            "mantidos os primeiros "
                + warnings.size()
                + " avisos do repositório; outros "
                + droppedWarnings
                + " foram descartados");
      }
      if (silencedEntries > 0) {
        Log.warn(
            "outras "
                + silencedEntries
                + " linhas de log por entrada da varredura foram silenciadas");
      }
      javaSources.sort(Comparator.comparing(JavaSource::relativePath));
      buildFiles.sort(Comparator.comparing(BuildFileEntry::relativePath));
      javaFilesSeen.accept(javaSources.size());
      return new RepositoryOutcome.Accepted<>(
          new RepositoryInventory(totalFileCount, javaSources, buildFiles), warnings);
    }
  }
}
