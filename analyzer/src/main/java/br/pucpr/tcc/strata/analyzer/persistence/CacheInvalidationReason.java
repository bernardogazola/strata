package br.pucpr.tcc.strata.analyzer.persistence;

public enum CacheInvalidationReason {
  CORRUPT,
  SCHEMA_VERSION,
  WORKTREE_DIRTY,
  CONFIG_CHANGED,
  HEAD_CHANGED
}
