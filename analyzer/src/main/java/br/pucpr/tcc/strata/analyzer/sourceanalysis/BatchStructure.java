package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import java.util.List;
import java.util.Objects;

public record BatchStructure(
    BatchElements elements, BatchDependencies dependencies, List<CodeMetric> metrics) {

  public BatchStructure {
    Objects.requireNonNull(elements, "elements");
    Objects.requireNonNull(dependencies, "dependencies");
    metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
  }

  public BatchStructure(BatchElements elements, BatchDependencies dependencies) {
    this(elements, dependencies, List.of());
  }

  public int size() {
    return elements.size() + dependencies.rows() + metrics.size();
  }
}
