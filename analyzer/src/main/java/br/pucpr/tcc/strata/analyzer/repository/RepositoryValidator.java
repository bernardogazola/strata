package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.bootstrap.UntrustedText;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;

public final class RepositoryValidator {

  private static final BooleanSupplier NEVER_CANCELLED = () -> false;
  private static final IntConsumer IGNORE_JAVA_FILES_SEEN = seen -> {};

  private final RepositoryLimits limits;
  private final ProjectLayoutDetector layoutDetector;
  private final GitMetadataGuard gitMetadataGuard;
  private final RepositoryWalker walker;

  public RepositoryValidator() {
    this(RepositoryLimits.PRODUCTION);
  }

  RepositoryValidator(RepositoryLimits limits) {
    if (!HermeticSystemReader.isInstalled()) {
      throw new IllegalStateException(
          "HermeticSystemReader.install() deve rodar antes de validar qualquer repositório");
    }
    this.limits = Objects.requireNonNull(limits, "limits");
    this.layoutDetector = new ProjectLayoutDetector(limits);
    this.gitMetadataGuard = new GitMetadataGuard(limits);
    this.walker = new RepositoryWalker(limits);
  }

  public RepositoryOutcome<RepositorySnapshot> inspect(Path claimedRoot) {
    return inspect(claimedRoot, NEVER_CANCELLED, IGNORE_JAVA_FILES_SEEN);
  }

  public RepositoryOutcome<RepositorySnapshot> inspect(
      Path claimedRoot, BooleanSupplier cancelled, IntConsumer javaFilesSeen) {
    Objects.requireNonNull(claimedRoot, "claimedRoot");
    Objects.requireNonNull(cancelled, "cancelled");
    Objects.requireNonNull(javaFilesSeen, "javaFilesSeen");
    if (!claimedRoot.isAbsolute()) {
      Log.warn("o caminho do repositório " + claimedRoot + " não é absoluto");
      return rejected(RepositoryIssue.pathChanged());
    }
    final Path real;
    try {
      real = claimedRoot.toRealPath();
    } catch (IOException moved) {
      Log.warn("o caminho do repositório " + claimedRoot + " não resolve mais: " + moved);
      return rejected(RepositoryIssue.pathChanged());
    }
    // Comparação byte a byte: o caminho do envelope já vem do canonicalize do Rust. Normalizar
    // esconderia uma troca de nome, e Files.isSameFile seguiria o link trocado.
    if (!real.equals(claimedRoot) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
      Log.warn("o caminho do repositório " + claimedRoot + " agora leva a " + real);
      return rejected(RepositoryIssue.pathChanged());
    }
    if (!isListable(real)) {
      return rejected(RepositoryIssue.rootUnreadable());
    }
    if (!gitMetadataGuard.admits(real)) {
      return rejected(RepositoryIssue.notAGitRepository());
    }
    return switch (readHead(real)) {
      case RepositoryOutcome.Rejected<HeadState>(RepositoryIssue reason) -> rejected(reason);
      case RepositoryOutcome.Accepted<HeadState>(HeadState head, List<RepositoryIssue> warnings) ->
          withInventory(real, head, warnings);
    };
  }

  public RepositoryOutcome<ValidatedRepository> validate(Path claimedRoot) {
    return validate(claimedRoot, NEVER_CANCELLED, IGNORE_JAVA_FILES_SEEN);
  }

  public RepositoryOutcome<ValidatedRepository> validate(
      Path claimedRoot, BooleanSupplier cancelled, IntConsumer javaFilesSeen) {
    return switch (inspect(claimedRoot, cancelled, javaFilesSeen)) {
      case RepositoryOutcome.Rejected<RepositorySnapshot>(RepositoryIssue reason) ->
          rejected(reason);
      case RepositoryOutcome.Accepted<RepositorySnapshot>(
              RepositorySnapshot snapshot,
              List<RepositoryIssue> warnings) ->
          withWarnings(discoverLayout(snapshot), warnings);
    };
  }

  public RepositoryOutcome<ValidatedRepository> discoverLayout(RepositorySnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    final ProjectLayout layout = layoutDetector.detect(snapshot.root(), snapshot.inventory());
    if (layout.sourceRoots().isEmpty()) {
      return rejected(RepositoryIssue.noJavaSources());
    }
    return new RepositoryOutcome.Accepted<>(new ValidatedRepository(snapshot, layout), List.of());
  }

  private static boolean isListable(Path real) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(real)) {
      entries.iterator().hasNext();
      return true;
    } catch (IOException | DirectoryIteratorException unreadable) {
      Log.error("não foi possível listar a raiz do repositório " + real, unreadable);
      return false;
    }
  }

  private static RepositoryOutcome<HeadState> readHead(Path real) {
    final File rootFile = real.toFile();
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder()
            .setFS(FS.DETECTED)
            .addCeilingDirectory(rootFile.getParentFile())
            .findGitDir(rootFile);
    if (builder.getGitDir() == null) {
      Log.warn("nenhum diretório git em " + real);
      return rejected(RepositoryIssue.notAGitRepository());
    }
    try (Repository repository = builder.setMustExist(true).build()) {
      return headOf(real, repository);
    } catch (IOException | RuntimeException refused) {
      Log.error("o JGit não consegue abrir o repositório em " + real, refused);
      return rejected(RepositoryIssue.notAGitRepository());
    }
  }

  private static RepositoryOutcome<HeadState> headOf(Path real, Repository repository)
      throws IOException {
    if (repository.isBare()) {
      return rejected(RepositoryIssue.isBare());
    }
    final Path workTree = repository.getWorkTree().toPath().toRealPath();
    if (!workTree.equals(real)) {
      Log.warn("o repositório encontrado em " + real + " tem a árvore de trabalho em " + workTree);
      return rejected(RepositoryIssue.notAGitRepository());
    }
    final Ref head = repository.exactRef(Constants.HEAD);
    if (head == null) {
      Log.warn("o repositório em " + real + " não tem HEAD");
      return rejected(RepositoryIssue.notAGitRepository());
    }
    final ObjectId commit = head.getObjectId();
    final String branch =
        head.isSymbolic()
            ? UntrustedText.clamp(
                Repository.shortenRefName(head.getTarget().getName()),
                AnalysisConstants.MAX_BRANCH_NAME_CHARS)
            : null;
    final boolean shallow = !repository.getObjectDatabase().getShallowCommits().isEmpty();
    final List<RepositoryIssue> warnings =
        commit == null ? List.of(RepositoryIssue.noHistory()) : List.of();
    return new RepositoryOutcome.Accepted<>(
        new HeadState(commit == null ? null : commit.name(), branch, shallow), warnings);
  }

  private RepositoryOutcome<RepositorySnapshot> withInventory(
      Path real, HeadState head, List<RepositoryIssue> headWarnings) {
    return switch (walker.walk(real)) {
      case RepositoryOutcome.Rejected<RepositoryInventory>(RepositoryIssue reason) ->
          rejected(reason);
      case RepositoryOutcome.Accepted<RepositoryInventory>(
              RepositoryInventory inventory,
              List<RepositoryIssue> walkWarnings) ->
          new RepositoryOutcome.Accepted<>(
              new RepositorySnapshot(real, displayName(real), head, inventory),
              capped(headWarnings, walkWarnings));
    };
  }

  private static RepositoryOutcome<ValidatedRepository> withWarnings(
      RepositoryOutcome<ValidatedRepository> layout, List<RepositoryIssue> warnings) {
    return switch (layout) {
      case RepositoryOutcome.Rejected<ValidatedRepository>(RepositoryIssue reason) ->
          rejected(reason);
      case RepositoryOutcome.Accepted<ValidatedRepository>(ValidatedRepository validated, var _) ->
          new RepositoryOutcome.Accepted<>(validated, warnings);
    };
  }

  private List<RepositoryIssue> capped(
      List<RepositoryIssue> headWarnings, List<RepositoryIssue> walkWarnings) {
    final List<RepositoryIssue> all = new ArrayList<>(headWarnings);
    all.addAll(walkWarnings);
    if (all.size() <= limits.maxWarnings()) {
      return all;
    }
    Log.warn(
        "descartados "
            + (all.size() - limits.maxWarnings())
            + " avisos do repositório acima do limite");
    return all.subList(0, limits.maxWarnings());
  }

  private static String displayName(Path real) {
    final Path name = real.getFileName();
    return UntrustedText.clamp(
        name == null ? real.toString() : name.toString(), AnalysisConstants.MAX_DISPLAY_NAME_CHARS);
  }

  private static <T> RepositoryOutcome<T> rejected(RepositoryIssue reason) {
    return new RepositoryOutcome.Rejected<>(reason);
  }
}
