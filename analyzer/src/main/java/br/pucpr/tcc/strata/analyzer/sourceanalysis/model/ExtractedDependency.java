package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.List;
import java.util.Objects;

public record ExtractedDependency(
    Dependency dependency,
    String filePath,
    boolean targetDeclared,
    boolean ambiguous,
    List<DependencyEvidence> evidence) {

  public ExtractedDependency {
    Objects.requireNonNull(dependency, "dependency");
    Objects.requireNonNull(filePath, "filePath");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    if (ambiguous
        && (targetDeclared || dependency.resolution() != ResolutionQuality.UNRESOLVED_EXTERNAL)) {
      throw new IllegalArgumentException(
          "uma linha ambígua é uma externa não resolvida: " + dependency);
    }
    final int expected =
        Math.min(dependency.occurrences(), AnalysisConstants.DEPENDENCY_EVIDENCE_CAP);
    if (evidence.size() != expected) {
      throw new IllegalArgumentException(
          "uma linha guarda min(occurrences, cap) = "
              + expected
              + " linhas de evidência, e não "
              + evidence.size());
    }
  }
}
