package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.util.List;
import java.util.Objects;

public record RunInputs(
    ValidatedRepository repository,
    String rootCommitId,
    AnalysisConfiguration configuration,
    List<SourceRoot> selectedRoots) {

  public RunInputs {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(configuration, "configuration");
    selectedRoots = List.copyOf(Objects.requireNonNull(selectedRoots, "selectedRoots"));
    if (selectedRoots.isEmpty()) {
      throw new IllegalArgumentException("uma execução analisa pelo menos uma raiz de código");
    }
    final List<SourceRoot> detected = repository.layout().sourceRoots();
    for (final SourceRoot root : selectedRoots) {
      if (!detected.contains(root)) {
        throw new IllegalArgumentException(
            "não é uma raiz de código detectada: " + root.relativePath());
      }
    }
  }
}
