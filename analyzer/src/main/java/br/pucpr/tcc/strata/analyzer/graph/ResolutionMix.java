package br.pucpr.tcc.strata.analyzer.graph;

public record ResolutionMix(long resolved, long inferred, long unresolvedExternal) {

  public static final ResolutionMix NONE = new ResolutionMix(0, 0, 0);

  public ResolutionMix {
    if (resolved < 0 || inferred < 0 || unresolvedExternal < 0) {
      throw new IllegalArgumentException(
          "uma contagem de ocorrências negativa: "
              + resolved
              + "/"
              + inferred
              + "/"
              + unresolvedExternal);
    }
  }

  public ResolutionMix plus(ResolutionMix other) {
    return new ResolutionMix(
        Math.addExact(resolved, other.resolved),
        Math.addExact(inferred, other.inferred),
        Math.addExact(unresolvedExternal, other.unresolvedExternal));
  }
}
