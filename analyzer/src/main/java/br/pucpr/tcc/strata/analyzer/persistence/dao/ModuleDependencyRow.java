package br.pucpr.tcc.strata.analyzer.persistence.dao;

import java.util.Objects;

public record ModuleDependencyRow(
    long pk,
    long filePk,
    String moduleName,
    String requiredName,
    boolean transitive,
    boolean isStatic,
    Integer line) {

  public ModuleDependencyRow {
    Objects.requireNonNull(moduleName, "moduleName");
    Objects.requireNonNull(requiredName, "requiredName");
    if (line != null && line < 1) {
      throw new IllegalArgumentException("uma linha é contada a partir de 1, ou null: " + line);
    }
  }
}
