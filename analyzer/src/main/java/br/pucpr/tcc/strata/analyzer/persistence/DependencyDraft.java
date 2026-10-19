package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.List;

record DependencyDraft(
    long sourcePk,
    CodeElementId target,
    boolean targetDeclared,
    DependencyType type,
    ResolutionQuality resolution,
    int occurrences,
    int weight,
    long filePk,
    List<EvidenceDraft> evidence) {

  DependencyDraft {
    evidence = List.copyOf(evidence);
  }

  record EvidenceDraft(Long fromMethodPk, Integer line, ResolutionQuality resolution) {}
}
