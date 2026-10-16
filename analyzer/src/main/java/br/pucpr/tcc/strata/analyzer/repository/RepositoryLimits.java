package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;

record RepositoryLimits(
    int maxEnumeratedFiles,
    int maxDirectoryDepth,
    int maxPathBytes,
    int maxBuildFileBytes,
    int maxGitConfigBytes,
    int maxWarnings,
    int maxPackedRefsBytes) {

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
        || maxPackedRefsBytes < 1) {
      throw new IllegalArgumentException("todo limite é positivo");
    }
  }
}
