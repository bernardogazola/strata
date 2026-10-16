package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
record SpikeReport(
    int schema,
    String measuredAt,
    String baseCommit,
    String measure,
    Rule rule,
    Verdict verdict,
    Environment environment,
    SpoonSettings spoon,
    List<RepositoryResult> repositories) {

  record Rule(
      String source,
      double maxParseFailureRatio,
      String ratioPopulation,
      long maxHeapBytes,
      String heapCriterion,
      int minPassingRepositories,
      int ofRepositories,
      int timeBoxHours,
      String onPass,
      String onFail) {}

  record Verdict(
      String outcome, int passingRepositories, int measuredRepositories, boolean allPass) {}

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

  record SpoonSettings(
      String version,
      int complianceLevel,
      boolean noClasspath,
      boolean ignoreSyntaxErrors,
      boolean commentEnabled,
      String resolution,
      String syntaxErrors,
      String moduleDescriptors) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RepositoryResult(
      String name,
      String origin,
      String commitId,
      String derivedFrom,
      List<String> mutations,
      String outcome,
      String refusal,
      Map<String, Integer> sourceRoots,
      Map<String, FileCounts> files,
      Ratio decision,
      Ratio allRoots,
      boolean pass,
      int parseFailureCount,
      List<Failure> parseFailures,
      List<String> tooLarge,
      Heap heap,
      Timing timing,
      Models models,
      Extraction extraction) {}

  record FileCounts(int total, int analyzed, int tooLarge, int parseFailures) {}

  record Ratio(int totalFiles, int parseFailures, double ratio) {}

  record Failure(String path, String rootKind, String cause) {}

  record Heap(long peakLiveBytes, long peakUsedBytes) {}

  record Timing(
      long wallMillis,
      long preParseMillis,
      long buildMillis,
      long visitMillis,
      long longestBuildMillis,
      String longestBuildRoot,
      long longestCancellationGapMillis,
      String longestCancellationGapBetween) {}

  record Models(int batches, int splitBatches, int quarantinedBatches, int builds) {}

  record Extraction(
      long types,
      long typeReferences,
      long resolvedTypeReferences,
      long executableReferences,
      long resolvedExecutableReferences) {}
}
