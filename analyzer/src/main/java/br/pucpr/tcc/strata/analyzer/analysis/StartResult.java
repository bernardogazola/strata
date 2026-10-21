package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Objects;

public sealed interface StartResult {

  record Started(AnalysisId analysisId, Runnable launch) implements StartResult {

    public Started {
      Objects.requireNonNull(analysisId, "analysisId");
      Objects.requireNonNull(launch, "launch");
    }
  }

  record AlreadyRunning() implements StartResult {}
}
