package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

public final class RootCommits {

  private RootCommits() {}

  public static Optional<String> firstParentRoot(
      HeadState head, Path root, BooleanSupplier cancelled) {
    Objects.requireNonNull(head, "head");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(cancelled, "cancelled");
    if (head.commitId() == null || head.shallow()) {
      return Optional.empty();
    }
    final FileRepositoryBuilder builder = RepositoryValidator.repositoryAt(root);
    if (builder.getGitDir() == null) {
      Log.warn(
          "nenhum diretório git em " + root + " para a varredura de commits raiz por primeiro pai");
      return Optional.empty();
    }
    try (Repository repository = builder.setMustExist(true).build();
        RevWalk walk = new RevWalk(repository)) {
      return Optional.of(walkFirstParents(walk, ObjectId.fromString(head.commitId()), cancelled));
    } catch (CancellationException cancellation) {
      throw cancellation;
    } catch (IOException | RuntimeException unreadable) {
      Log.warn(
          "a varredura de commits raiz por primeiro pai em " + root + " falhou: " + unreadable);
      return Optional.empty();
    }
  }

  private static String walkFirstParents(RevWalk walk, ObjectId head, BooleanSupplier cancelled)
      throws IOException {
    walk.setRetainBody(false);
    RevCommit commit = walk.parseCommit(head);
    while (true) {
      if (cancelled.getAsBoolean()) {
        throw new CancellationException("varredura de commits raiz por primeiro pai cancelada");
      }
      if (commit.getParentCount() == 0) {
        return commit.name();
      }
      commit = commit.getParent(0);
      walk.parseHeaders(commit);
    }
  }
}
