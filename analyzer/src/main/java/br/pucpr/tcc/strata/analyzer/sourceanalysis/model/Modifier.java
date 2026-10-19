package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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

  public static Set<Modifier> canonical(Collection<Modifier> modifiers) {
    return modifiers.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(modifiers));
  }

  public String keyword() {
    return keyword;
  }
}
