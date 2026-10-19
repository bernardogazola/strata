package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

public enum DependencyType {
  IMPORTS,
  EXTENDS,
  IMPLEMENTS,
  CALLS,
  FIELD_TYPE,
  PARAMETER_TYPE,
  RETURN_TYPE,
  ANNOTATION_REFERENCE,
  MODULE_DEPENDENCY;

  public boolean isTypeEdge() {
    return this != MODULE_DEPENDENCY;
  }
}
