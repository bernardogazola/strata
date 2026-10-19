package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

public enum Modifier {
  PUBLIC("public"),
  PROTECTED("protected"),
  PRIVATE("private"),
  ABSTRACT("abstract"),
  DEFAULT("default"),
  STATIC("static"),
  SEALED("sealed"),
  NON_SEALED("non-sealed"),
  FINAL("final"),
  TRANSIENT("transient"),
  VOLATILE("volatile"),
  SYNCHRONIZED("synchronized"),
  NATIVE("native"),
  STRICTFP("strictfp");

  private final String keyword;

  Modifier(String keyword) {
    this.keyword = keyword;
  }

  public String keyword() {
    return keyword;
  }
}
