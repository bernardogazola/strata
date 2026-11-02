package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Objects;

public record EdgeEvidenceRow(
    DependencyType type,
    ResolutionQuality resolution,
    String filePath,
    Integer line,
    EvidenceMethodRow fromMethod,
    EvidenceMethodRow toMethod) {

  public EdgeEvidenceRow {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resolution, "resolution");
    if (line != null && line < 1) {
      throw new IllegalArgumentException("uma linha é contada a partir de 1, ou null: " + line);
    }
  }
}
