package br.pucpr.tcc.strata.analyzer.sourceanalysis;

public enum ParseFailureCause {
  SYNTAX_ERROR,

  MODEL_BUILD_CRASH,

  DROPPED_BY_SPOON,

  EXTRACTION_CRASH,

  UNREADABLE
}
