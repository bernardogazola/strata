package br.pucpr.tcc.strata.analyzer.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Assumptions;

public final class TestRepositories {

  public static final PersonIdent FIXTURE_IDENT =
      new PersonIdent(
          "Strata Fixture",
          "fixtures@strata.invalid",
          Instant.ofEpochSecond(1_704_164_645L),
          ZoneOffset.UTC);

  private TestRepositories() {}

  public static Path write(Path root, String relativePath, String content) throws IOException {
    final Path file = root.resolve(relativePath);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  public static Git init(Path directory) throws GitAPIException {
    return Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call();
  }

  public static RevCommit commitAll(Git git, String message) throws GitAPIException {
    git.add().addFilepattern(".").call();
    return git.commit()
        .setMessage(message)
        .setAuthor(FIXTURE_IDENT)
        .setCommitter(FIXTURE_IDENT)
        .setNoVerify(true)
        .setSign(false)
        .call();
  }

  public static Path bare(Path directory) throws GitAPIException {
    Git.init()
        .setBare(true)
        .setDirectory(directory.toFile())
        .setInitialBranch("main")
        .call()
        .close();
    return directory;
  }

  public static Path linkedWorktree(Path mainWorkTree, Path worktree, String branch)
      throws IOException {
    final Path adminDirectory = mainWorkTree.resolve(".git").resolve("worktrees").resolve("wt");
    Files.createDirectories(adminDirectory);
    Files.createDirectories(worktree);
    Files.writeString(adminDirectory.resolve("HEAD"), "ref: refs/heads/" + branch + "\n");
    Files.writeString(adminDirectory.resolve("commondir"), "../..\n");
    Files.writeString(adminDirectory.resolve("gitdir"), worktree.resolve(".git") + "\n");
    Files.writeString(worktree.resolve(".git"), "gitdir: " + adminDirectory + "\n");
    return worktree;
  }

  public static Path symlink(Path link, Path target) throws IOException {
    try {
      Files.createDirectories(link.getParent());
      return Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | FileSystemException refused) {
      Assumptions.abort(
          "esta máquina se recusa a criar links simbólicos (no Windows: SeCreateSymbolicLinkPrivilege"
              + " ou o Modo de Desenvolvedor): "
              + refused);
      throw refused;
    }
  }

  public static Path corpus(String fixture) throws IOException {
    return corpusRoot().resolve(fixture).toRealPath();
  }

  public static Path corpusRoot() throws IOException {
    return Path.of(
            Optional.ofNullable(System.getProperty("strata.repository.fixtures"))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "-Dstrata.repository.fixtures não foi definido; veja o surefire")))
        .toRealPath();
  }

  public static RepositoryInventory inventoryOf(Path root) throws IOException {
    final List<JavaSource> javaSources = new ArrayList<>();
    final List<BuildFileEntry> buildFiles = new ArrayList<>();
    final int[] total = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
            final boolean isGit =
                !directory.equals(root) && ".git".equals(directory.getFileName().toString());
            return isGit ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            final String name = file.getFileName().toString();
            if (".git".equals(name)) {
              return FileVisitResult.CONTINUE;
            }
            total[0]++;
            final String relative = posix(root.relativize(file));
            if (attrs.isRegularFile() && name.endsWith(".java")) {
              javaSources.add(
                  new JavaSource(relative, attrs.size(), attrs.lastModifiedTime().toMillis()));
            }
            if (BuildSystemDetector.BUILD_FILE_NAMES.contains(name)) {
              buildFiles.add(new BuildFileEntry(relative, attrs.isRegularFile(), attrs.size()));
            }
            return FileVisitResult.CONTINUE;
          }
        });
    javaSources.sort(Comparator.comparing(JavaSource::relativePath));
    buildFiles.sort(Comparator.comparing(BuildFileEntry::relativePath));
    return new RepositoryInventory(total[0], javaSources, buildFiles);
  }

  public static String posix(Path relative) {
    final StringBuilder joined = new StringBuilder();
    for (final Path name : relative) {
      if (!joined.isEmpty()) {
        joined.append('/');
      }
      joined.append(name);
    }
    return joined.toString();
  }
}
