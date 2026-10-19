package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

public enum ElementKind {
  PACKAGE("pkg:"),
  TYPE("type:"),
  METHOD("method:"),
  FILE("file:");

  private final String prefix;

  ElementKind(String prefix) {
    this.prefix = prefix;
  }

  public String prefix() {
    return prefix;
  }
}
