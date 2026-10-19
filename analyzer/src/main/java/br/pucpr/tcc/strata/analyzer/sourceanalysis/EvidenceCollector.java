package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.List;
import java.util.Objects;

final class EvidenceCollector {

  private final String filePath;

  EvidenceCollector(String filePath) {
    this.filePath = Objects.requireNonNull(filePath, "filePath");
  }

  void add(Occurrence occurrence) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  List<ExtractedDependency> collect() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  record Occurrence(
      CodeElementId source,
      CodeElementId target,
      DependencyType type,
      ResolutionQuality resolution,
      boolean targetDeclared,
      int line,
      int offset,
      CodeElementId fromMethod,
      CodeElementId toMethod) {

    Occurrence {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(resolution, "resolution");
    }
  }
}
