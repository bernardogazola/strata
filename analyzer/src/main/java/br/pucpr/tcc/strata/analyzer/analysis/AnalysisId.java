package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;
import java.util.regex.Pattern;

public record AnalysisId(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("^an_[0-7][0-9A-HJKMNP-TV-Z]{25}$");

  static final String MALFORMED =
      "analysisId não é an_ seguido de 26 caracteres em base32 de Crockford";

  public AnalysisId {
    Objects.requireNonNull(value, "value");
    if (!GRAMMAR.matcher(value).matches()) {
      throw new IllegalArgumentException(MALFORMED);
    }
  }

  public static AnalysisId parse(String wire) {
    if (wire == null) {
      throw new IllegalArgumentException(MALFORMED);
    }
    return new AnalysisId(wire);
  }
}
