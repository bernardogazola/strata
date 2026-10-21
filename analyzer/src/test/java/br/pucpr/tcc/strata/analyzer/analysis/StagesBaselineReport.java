package br.pucpr.tcc.strata.analyzer.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record StagesBaselineReport(
    int schema,
    String measuredAt,
    String baseCommit,
    String measure,
    Settings settings,
    Environment environment,
    List<WrittenException> exceptions,
    List<RepositoryResult> repositories) {

  record Settings(
      String spoonVersion,
      String rootOrder,
      Map<String, SelectionPayload> selections,
      List<String> cacheStates,
      int runsPerConfiguration,
      String statistic,
      int progressThrottleMillis,
      int cancellationDeadlineMillis,
      int cpuPoolSize,
      String harness,
      Baseline baseline,
      String firstTick,
      String gapCensus) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  record SelectionPayload(
      boolean includeTests,
      String historyScope,
      Integer historyScopeCommits,
      List<String> sourceRootIds) {}

  record Baseline(String file, String measuredAt, String baseCommit) {}

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
      List<String> garbageCollectors,
      boolean assertionsEnabled,
      Host host) {}

  record Host(
      String os,
      String arch,
      String cpu,
      int logicalCores,
      long ramBytes,
      String storage,
      String jvm) {}

  record WrittenException(
      @JsonProperty("class") String gapClass,
      String description,
      long boundMillis,
      String repository,
      String selection,
      String batch,
      long referenceBoundMillis,
      int deadlineMillis) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RepositoryResult(
      String name,
      String origin,
      String commitId,
      String derivedFrom,
      String sizeClass,
      boolean shallow,
      long commitCount,
      Size size,
      Walk prologueWalk,
      List<Configuration> configurations) {

    RepositoryResult with(Configuration configuration) {
      final List<Configuration> grown = new ArrayList<>(configurations);
      grown.add(configuration);
      return new RepositoryResult(
          name,
          origin,
          commitId,
          derivedFrom,
          sizeClass,
          shallow,
          commitCount,
          size,
          prologueWalk,
          List.copyOf(grown));
    }
  }

  record Size(long entries, long javaFiles) {}

  record Walk(long checks, long longestGapMillis, long wallMillis) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Configuration(
      String selection,
      String configHash,
      String cacheState,
      String outcome,
      Completeness completeness,
      Warnings warnings,
      Models models,
      List<StageResult> stages,
      Series analysisWallMillis,
      Series bindMillis,
      FirstTick firstTickMillis,
      Heap heap,
      Series cacheBytes,
      Elements elements,
      Dependencies dependencies,
      Map<String, Long> metricRows,
      List<String> baselineDifferences,
      Cancellation cancellation,
      List<String> runIds) {}

  record Warnings(Map<String, Long> emitted, Map<String, Long> total) {}

  record Models(long batches, long builds) {}

  record StageResult(String stage, String status, Map<String, Long> counters, Series durationMs) {}

  record Series(long median, List<Long> runs) {

    static Series of(List<Long> runs) {
      final List<Long> sorted = runs.stream().sorted().toList();
      return new Series(sorted.get(sorted.size() / 2), List.copyOf(runs));
    }
  }

  record FirstTick(long median, List<Long> runs, String stage, List<Long> runsMicros) {}

  record Heap(Peak peakLiveBytes, Peak peakUsedBytes) {}

  record Peak(long max, long median, List<Long> runs) {

    static Peak of(List<Long> runs) {
      final Series series = Series.of(runs);
      return new Peak(
          runs.stream().mapToLong(Long::longValue).max().orElse(0), series.median(), series.runs());
    }
  }

  record Elements(
      long packages,
      long types,
      long methods,
      long files,
      long externalTypes,
      long sourceFiles,
      long sourceRoots,
      Map<String, Long> sourceFileStatuses) {}

  record Dependencies(long rows, long occurrences, long evidenceRows, long moduleRows) {}

  record Cancellation(
      long longestGapMillis,
      @JsonProperty("class") String gapClass,
      String unitOrSite,
      long over1000,
      long over2000,
      long tailMillis,
      long over250,
      long checks,
      String closedBy,
      long censusWallMillis,
      List<Gap> top) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Gap(
      long millis,
      @JsonProperty("class") String gapClass,
      Map<String, Integer> samples,
      String closedBy,
      String batch,
      long offsetMillis) {

    static Gap of(GapCensus.Classified classified) {
      return new Gap(
          classified.millis(),
          classified.gapClass(),
          classified.samples(),
          classified.closedBy(),
          classified.batch(),
          classified.offsetMillis());
    }
  }
}
