package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Objects;

public record EvidenceRow(
    long dependencyPk,
    Long fromMethodPk,
    Long toMethodPk,
    Long filePk,
    Integer line,
    ResolutionQuality resolution) {

  public EvidenceRow {
    Objects.requireNonNull(resolution, "resolution");
    if (line != null && line < 1) {
      throw new IllegalArgumentException("uma linha é contada a partir de 1, ou null: " + line);
    }
  }
}
