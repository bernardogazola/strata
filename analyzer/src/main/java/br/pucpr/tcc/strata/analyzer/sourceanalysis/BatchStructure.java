package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.Objects;

public record BatchStructure(BatchElements elements, BatchDependencies dependencies) {

  public BatchStructure {
    Objects.requireNonNull(elements, "elements");
    Objects.requireNonNull(dependencies, "dependencies");
  }

  public int size() {
    return elements.size() + dependencies.rows();
  }
}
