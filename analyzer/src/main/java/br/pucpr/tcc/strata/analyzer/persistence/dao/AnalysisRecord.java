package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import java.util.Objects;

public record AnalysisRecord(
    long pk,
    String analysisId,
    AnalysisStatus status,
    long startedAt,
    Long finishedAt,
    String headCommitId,
    String analyzerVersion,
    int schemaVersion,
    int protocolVersion,
    String configHash,
    String sourceFingerprint,
    boolean includeTests,
    HistoryScope historyScope,
    Integer historyScopeCommits,
    AnalysisCounters counters,
    String failureCode,
    String failureDetail) {

  public AnalysisRecord {
    Objects.requireNonNull(analysisId, "analysisId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(analyzerVersion, "analyzerVersion");
    Objects.requireNonNull(configHash, "configHash");
    Objects.requireNonNull(historyScope, "historyScope");
    Objects.requireNonNull(counters, "counters");
  }
}
