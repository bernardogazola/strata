package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.repository.ProjectLayout;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class SourceSelection {

  static final String UNKNOWN_SOURCE_ROOT =
      "um id de raiz de código selecionado não é raiz de código deste repositório";

  static final String EMPTY_SELECTION = "a seleção não tem nenhum arquivo-fonte Java";

  private static final String ANALYSIS_START = "analysis.start";

  private static final List<SourceRootKind> KIND_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private SourceSelection() {}

  public static List<SourceRoot> select(ProjectLayout layout, AnalysisConfiguration configuration) {
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(configuration, "configuration");
    final List<SourceRoot> selected = ordered(layout, chosen(layout, configuration));
    if (selected.isEmpty()) {
      throw refused(EMPTY_SELECTION);
    }
    final long found = selected.stream().mapToLong(SourceRoot::javaFileCount).sum();
    if (found > AnalysisConstants.MAX_JAVA_SOURCES) {
      throw new StageFailedException(
          ErrorCodes.tooManyJavaSources(
              AnalysisConstants.MAX_JAVA_SOURCES, Math.toIntExact(found)));
    }
    return selected;
  }

  private static Predicate<SourceRoot> chosen(
      ProjectLayout layout, AnalysisConfiguration configuration) {
    final List<String> ids = configuration.sourceRootIds();
    if (ids == null) {
      return root -> root.kind() != SourceRootKind.TEST || configuration.includeTests();
    }
    final Set<String> detected =
        layout.sourceRoots().stream().map(root -> root.id().value()).collect(Collectors.toSet());
    if (!detected.containsAll(ids)) {
      throw refused(UNKNOWN_SOURCE_ROOT);
    }
    final Set<String> wanted = Set.copyOf(ids);
    return root -> wanted.contains(root.id().value());
  }

  private static List<SourceRoot> ordered(ProjectLayout layout, Predicate<SourceRoot> chosen) {
    final List<SourceRoot> selected = new ArrayList<>();
    for (final SourceRootKind kind : KIND_ORDER) {
      for (final SourceRoot root : layout.sourceRoots()) {
        if (root.kind() == kind && chosen.test(root)) {
          selected.add(root);
        }
      }
    }
    return List.copyOf(selected);
  }

  private static StageFailedException refused(String detail) {
    return new StageFailedException(ErrorCodes.invalidPayload(ANALYSIS_START, detail));
  }
}
