package br.pucpr.tcc.strata.analyzer.repository;

import java.util.Objects;

public record JavaSource(String relativePath, long sizeBytes, long lastModifiedMillis) {

  public JavaSource {
    Objects.requireNonNull(relativePath, "relativePath");
  }
}
