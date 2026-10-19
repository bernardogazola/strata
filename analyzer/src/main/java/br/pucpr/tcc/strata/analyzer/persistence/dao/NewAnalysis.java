package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import java.util.Objects;

public record NewAnalysis(
    String analysisId,
    long startedAt,
    String headCommitId,
    String analyzerVersion,
    int schemaVersion,
    int protocolVersion,
    String configHash,
    String configJson,
    String sourceFingerprint,
    boolean includeTests,
    HistoryScope historyScope,
    Integer historyScopeCommits) {

  public NewAnalysis {
    Objects.requireNonNull(analysisId, "analysisId");
    Objects.requireNonNull(analyzerVersion, "analyzerVersion");
    Objects.requireNonNull(configHash, "configHash");
    Objects.requireNonNull(configJson, "configJson");
    Objects.requireNonNull(historyScope, "historyScope");
    if ((historyScope == HistoryScope.LAST_N_COMMITS) != (historyScopeCommits != null)) {
      throw new IllegalArgumentException(
          "historyScopeCommits existe se e somente se historyScope é LAST_N_COMMITS");
    }
  }
}
