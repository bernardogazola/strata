package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;

record RepositoryLimits(
    int maxEnumeratedFiles,
    int maxDirectoryDepth,
    int maxPathBytes,
    int maxBuildFileBytes,
    int maxGitConfigBytes,
    int maxWarnings,
    int maxPackedRefsBytes,
    int maxGitAlternates,
    int maxGitAlternatesDepth,
    int maxLooseObjects,
    long maxPackIndexBytes,
    long maxPackIndexTotalBytes,
    long maxCommitGraphBytes) {

  static final RepositoryLimits PRODUCTION =
      new RepositoryLimits(
          AnalysisConstants.MAX_ENUMERATED_FILES,
          AnalysisConstants.MAX_DIRECTORY_DEPTH,
          AnalysisConstants.MAX_PATH_BYTES,
          AnalysisConstants.MAX_BUILD_FILE_BYTES,
          AnalysisConstants.MAX_GIT_CONFIG_BYTES,
          AnalysisConstants.MAX_REPOSITORY_WARNINGS,
          AnalysisConstants.MAX_PACKED_REFS_BYTES);

  RepositoryLimits {
    if (maxEnumeratedFiles < 1
        || maxDirectoryDepth < 1
        || maxPathBytes < 1
        || maxBuildFileBytes < 1
        || maxGitConfigBytes < 1
        || maxWarnings < 1
        || maxPackedRefsBytes < 1
        || maxGitAlternates < 1
        || maxGitAlternatesDepth < 1
        || maxLooseObjects < 1
        || maxPackIndexBytes < 1
        || maxPackIndexTotalBytes < 1
        || maxCommitGraphBytes < 1) {
      throw new IllegalArgumentException("todo limite é positivo");
    }
  }

  RepositoryLimits(
      int maxEnumeratedFiles,
      int maxDirectoryDepth,
      int maxPathBytes,
      int maxBuildFileBytes,
      int maxGitConfigBytes,
      int maxWarnings,
      int maxPackedRefsBytes) {
    this(
        maxEnumeratedFiles,
        maxDirectoryDepth,
        maxPathBytes,
        maxBuildFileBytes,
        maxGitConfigBytes,
        maxWarnings,
        maxPackedRefsBytes,
        AnalysisConstants.MAX_GIT_ALTERNATES,
        AnalysisConstants.MAX_GIT_ALTERNATES_DEPTH,
        AnalysisConstants.MAX_LOOSE_OBJECTS,
        AnalysisConstants.MAX_PACK_INDEX_BYTES,
        AnalysisConstants.MAX_PACK_INDEX_TOTAL_BYTES,
        AnalysisConstants.MAX_COMMIT_GRAPH_BYTES);
  }
}
