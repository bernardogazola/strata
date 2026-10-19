package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import java.util.Objects;

public record ModuleDependency(
    String moduleName,
    String requiredName,
    boolean transitive,
    boolean isStatic,
    String filePath,
    int line) {

  public ModuleDependency {
    Objects.requireNonNull(moduleName, "moduleName");
    Objects.requireNonNull(requiredName, "requiredName");
    Objects.requireNonNull(filePath, "filePath");
    if (moduleName.isEmpty() || requiredName.isEmpty()) {
      throw new IllegalArgumentException("um nome de módulo nunca é vazio");
    }
    if (line < 0) {
      throw new IllegalArgumentException("não é uma linha: " + line);
    }
  }
}
