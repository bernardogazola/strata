package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.OptionalDouble;

public record ResolutionCounts(long resolved, long inferred, long unresolved, long ambiguous) {

  public static final ResolutionCounts NONE = new ResolutionCounts(0, 0, 0, 0);

  public ResolutionCounts {
    if (resolved < 0 || inferred < 0 || unresolved < 0 || ambiguous < 0 || ambiguous > unresolved) {
      throw new IllegalArgumentException(
          "as contagens devem ser não negativas e ambiguous <= unresolved: "
              + resolved
              + " / "
              + inferred
              + " / "
              + unresolved
              + " / "
              + ambiguous);
    }
  }

  public OptionalDouble rate() {
    final long total = resolved + inferred + unresolved;
    return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) resolved / total);
  }
}
