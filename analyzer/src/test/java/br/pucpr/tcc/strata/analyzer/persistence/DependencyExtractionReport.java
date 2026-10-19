package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Environment;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.FileOutcomes;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Models;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
record DependencyExtractionReport(
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
      int dependencyEvidenceCap,
      int maxModelSourceBytes,
      String rootOrder,
      Map<String, String> selections,
      Baseline baseline) {}

  record Baseline(String file, String measuredAt, String baseCommit) {}

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
      Elements elements,
      Dependencies dependencies,
      Evidence evidence,
      Modules modules,
      Edges edges,
      Checks checks,
      Drops drops,
      Map<String, Integer> extractionIssues,
      Write write,
      Timing timing,
      UnitFunctions unitFunctions,
      Models models,
      Heap heap,
      Long cacheBytes,
      Long baselineCacheBytes,
      ExcludedConstructs excludedConstructs) {}

  record Elements(
      long packages,
      long types,
      long methods,
      long files,
      long externalTypes,
      long sourceFiles,
      long sourceRoots,
      Map<String, Long> sourceFileStatuses) {}

  record Dependencies(
      long rows,
      long occurrences,
      Map<String, Long> rowsByType,
      Map<String, Long> occurrencesByType,
      long selfRows,
      long selfOccurrences,
      long rowsToExternal,
      long occurrencesToExternal,
      Map<String, Long> resolutionPairs,
      Spread occurrencesPerRow,
      LargestRow largestRow) {}

  record Spread(int p50, int p90, int p99, int max, double mean) {}

  record LargestRow(String source, String target, String type, int occurrences) {}

  record Evidence(
      long rows,
      long rowsAtCap,
      long uncapped,
      double trimmedPercent,
      long withFromMethod,
      long withoutLine,
      Map<String, Long> byResolution) {}

  record Modules(
      long rows,
      long files,
      long declaringModules,
      long transitive,
      long isStatic,
      long withoutLine) {}

  record Edges(
      long edges,
      long selfEdges,
      long toInternal,
      long toExternal,
      Map<String, Long> byResolution,
      Map<String, Long> byDistinctTypeCount,
      long occurrenceTotal,
      long weightTotal) {}

  record Checks(
      long foreignKeyViolations,
      long danglingEndpoints,
      long sourcesNotInternalTypes,
      long targetsNotTypes,
      long moduleDependencyInDependency,
      long invalidResolutionPairs,
      long evidenceCountMismatches,
      long evidenceFileMismatches,
      long fromMethodOwnerMismatches,
      long evidenceWeakerThanRow,
      long toMethodsWritten,
      long weightMismatches,
      long externalRowsMalformed,
      long externalsWithoutDependency,
      boolean edgeTotalsMatchRows,
      List<String> baselineDifferences,
      boolean flushRuleReproduced,
      int elementOnlyAccepts,
      boolean writtenEqualsReadBack,
      boolean censusPassSameOutcomes) {}

  record Drops(
      Map<String, Long> extractionByReason,
      long extractionTotal,
      int unresolvedDeclaredTarget,
      long pendingPeakRows) {}

  record Write(
      long beginMillis,
      int acceptCalls,
      List<Flush> flushes,
      long bufferingMillis,
      Flush end,
      long sinkMillis,
      long walPeakBytes,
      long probeMillis) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Flush(
      int batches,
      int rows,
      int elementRows,
      int dependencyRows,
      int evidenceRows,
      int moduleRows,
      Integer fileElements,
      long millis,
      long externalRowsWritten,
      long dependencyRowsWritten,
      long evidenceRowsWritten,
      long moduleRowsWritten,
      long pendingAfter,
      long walBytes) {}

  record Timing(
      long wallMillis,
      long preParseMillis,
      long buildMillis,
      long visitMillis,
      long extractionMillis) {}

  record UnitFunctions(
      int units,
      long elementMillis,
      long dependencyMillis,
      long totalMillis,
      Unit longestUnit,
      List<Unit> slowestUnits,
      long censusWallMillis) {}

  record Unit(String path, long totalMicros, long elementMicros, long dependencyMicros) {}

  record Heap(
      long peakLiveBytes,
      long peakUsedBytes,
      Long baselinePeakLiveBytes,
      Long baselinePeakUsedBytes) {}

  record ExcludedConstructs(
      long basePairs,
      long baseInternalPairs,
      Map<String, Construct> constructs,
      Union union,
      long staticFieldWrites,
      Map<String, Integer> scanDrops,
      long scanCrashes,
      long scanMillis) {}

  record Construct(
      long sites,
      long withoutTarget,
      long droppedTarget,
      long outsideSurvivingSource,
      long occurrences,
      long selfOccurrences,
      long pairs,
      long pairsAdded,
      long pairsAddedInternal,
      double addedInternalPercent) {}

  record Union(long pairsAdded, long pairsAddedInternal, double addedInternalPercent) {}
}
