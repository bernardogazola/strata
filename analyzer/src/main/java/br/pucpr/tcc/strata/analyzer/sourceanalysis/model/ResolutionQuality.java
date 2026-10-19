package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

public enum ResolutionQuality {
  RESOLVED(2),
  INFERRED(1),
  UNRESOLVED_EXTERNAL(0);

  private final int strength;

  ResolutionQuality(int strength) {
    this.strength = strength;
  }

  public static ResolutionQuality weakest(ResolutionQuality first, ResolutionQuality second) {
    return second.strength < first.strength ? second : first;
  }
}
