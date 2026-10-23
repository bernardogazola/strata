package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.UntrustedText;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record RepositoryDescription(
    BuildSystem buildSystem,
    String cachedAnalysisId,
    List<String> declaredModules,
    String defaultBranch,
    String displayName,
    String headCommitId,
    @JsonProperty("isBare") boolean isBare,
    @JsonProperty("isGitRepository") boolean isGitRepository,
    @JsonProperty("isShallow") boolean isShallow,
    List<Root> sourceRoots,
    int totalFileCount) {

  public RepositoryDescription {
    Objects.requireNonNull(buildSystem, "buildSystem");
    Objects.requireNonNull(displayName, "displayName");
    declaredModules = List.copyOf(Objects.requireNonNull(declaredModules, "declaredModules"));
    sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots"));
  }

  public static RepositoryDescription of(ValidatedRepository repository) {
    final var snapshot = repository.snapshot();
    return new RepositoryDescription(
        repository.layout().buildSystem(),
        null,
        describedModules(repository.layout().declaredModules()),
        snapshot.head().branch(),
        snapshot.displayName(),
        snapshot.head().commitId(),
        false,
        true,
        snapshot.head().shallow(),
        repository.layout().sourceRoots().stream().map(Root::of).toList(),
        snapshot.inventory().totalFileCount());
  }

  private static List<String> describedModules(List<String> declared) {
    return declared.stream()
        .limit(AnalysisConstants.MAX_DESCRIBED_MODULES)
        .map(module -> UntrustedText.clamp(module, AnalysisConstants.MAX_PATH_BYTES))
        .toList();
  }

  public record Root(String id, int javaFileCount, SourceRootKind kind, String label) {

    static Root of(SourceRoot root) {
      return new Root(
          root.id().value(),
          root.javaFileCount(),
          root.kind(),
          UntrustedText.clamp(root.relativePath(), AnalysisConstants.MAX_PATH_BYTES));
    }
  }
}
