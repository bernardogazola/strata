package br.pucpr.tcc.strata.analyzer.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ElementExtractionReport(
    int schema,
    String measuredAt,
    String baseCommit,
    String measure,
    Settings settings,
    Environment environment,
    List<RepositoryResult> repositories) {

  record Settings(
      String spoonVersion,
      int maxElementIdBytes,
      int elementFlushRows,
      int rowsPerInsert,
      int maxModelSourceBytes,
      String rootOrder,
      Map<String, String> selections) {}

  record Environment(
      String os,
      String arch,
      String cpu,
      int logicalCores,
      long ramBytes,
      String storage,
      String runtime,
      String jvm,
      List<String> jvmFlags,
      long maxHeapBytes,
      List<String> garbageCollectors) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RepositoryResult(
      String name,
      String origin,
      String commitId,
      String derivedFrom,
      Map<String, Integer> sourceRoots,
      List<SelectionResult> selections) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SelectionResult(
      String selection,
      Map<String, Integer> roots,
      String outcome,
      FileOutcomes files,
      Rows rows,
      Checks checks,
      Map<String, Integer> extractionIssues,
      Map<String, Integer> analysisIssues,
      Write write,
      Timing timing,
      Models models,
      Long cacheBytes,
      Heap heap,
      List<Failure> parseFailures,
      List<Duplicate> duplicates) {}

  record FileOutcomes(
      int total,
      int analyzed,
      int tooLarge,
      int parseFailed,
      Map<String, Integer> parseFailedByCause) {}

  record Rows(
      long packages,
      long types,
      long methods,
      long files,
      long sourceFiles,
      long sourceRoots,
      Map<String, Long> sourceFileStatuses) {}

  record Checks(
      long foreignKeyViolations,
      long fileRowsJoined,
      long distinctSourceFilesJoined,
      long typesAccepted,
      long distinctTypeIdsAccepted) {}

  record Write(
      long beginMillis,
      int acceptCalls,
      List<Flush> flushes,
      long bufferingMillis,
      EndWrite end,
      long sinkMillis) {}

  record Flush(int batches, int rows, long millis) {}

  record EndWrite(int batches, int rows, int fileElements, long millis) {}

  record Timing(
      long wallMillis,
      long preParseMillis,
      long buildMillis,
      long visitMillis,
      long extractionMillis) {}

  record Models(int batches, int splitBatches, int quarantinedBatches, int builds) {}

  record Heap(long peakLiveBytes, long peakUsedBytes) {}

  record Failure(String path, String rootKind, String cause) {}

  record Duplicate(String filePath, String firstFilePath) {}
}
