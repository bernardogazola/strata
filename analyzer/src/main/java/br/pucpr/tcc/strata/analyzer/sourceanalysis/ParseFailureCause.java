package br.pucpr.tcc.strata.analyzer.sourceanalysis;

public enum ParseFailureCause {
  SYNTAX_ERROR,

  NESTING_TOO_DEEP,

  PRE_PARSE_CRASH,

  MODEL_BUILD_CRASH,

  DROPPED_BY_SPOON,

  EXTRACTION_CRASH,

  UNREADABLE
}
