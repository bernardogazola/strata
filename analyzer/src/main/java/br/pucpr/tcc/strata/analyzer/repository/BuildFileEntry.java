package br.pucpr.tcc.strata.analyzer.repository;

import java.util.Objects;

public record BuildFileEntry(String relativePath, boolean regularFile, long sizeBytes) {

  public BuildFileEntry {
    Objects.requireNonNull(relativePath, "relativePath");
  }
}
