package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.FileOutcomes;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Models;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
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
      Baseline baseline,
      List<String> runtimeImageModules) {}

  record Baseline(String file, String measuredAt, String baseCommit) {}

  record Environment(
      @JsonUnwrapped ElementExtractionReport.Environment host, boolean assertionsEnabled) {

    static Environment ofThisJvm() {
      final OperatingSystemMXBean system =
          ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
      final List<String> flags =
          ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
              .filter(argument -> argument.startsWith("-X"))
              .toList();
      return new Environment(
          new ElementExtractionReport.Environment(
              System.getProperty("os.name") + " " + System.getProperty("os.version"),
              System.getProperty("os.arch"),
              System.getProperty("strata.host.cpu", "not recorded: pass -Dstrata.host.cpu"),
              Runtime.getRuntime().availableProcessors(),
              system.getTotalMemorySize(),
              System.getProperty("strata.host.storage", "not recorded: pass -Dstrata.host.storage"),
              "full JDK under Maven Surefire, not the jlink image the application ships",
              System.getProperty("java.vm.vendor")
                  + " "
                  + System.getProperty("java.runtime.version"),
              flags,
              Runtime.getRuntime().maxMemory(),
              ManagementFactory.getGarbageCollectorMXBeans().stream()
                  .map(GarbageCollectorMXBean::getName)
                  .toList()),
          assertsRun());
    }

    private static boolean assertsRun() {
      boolean enabled = false;
      assert enabled = true;
      return enabled;
    }
  }

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
      Resolution resolution,
      Ambiguity ambiguous,
      ToMethod toMethod,
      Externals externals,
      OwnPackageExternals ownPackageExternals,
      FanOutExternal fanOutExternal,
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
      Map<String, Long> resolutionPairOccurrences,
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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Resolution(
      long resolved,
      long inferred,
      long unresolved,
      long ambiguous,
      Double rate,
      Map<String, Long> rowOccurrences,
      long ambiguousRowOccurrences,
      Long writerDroppedOccurrences) {}

  record Ambiguity(
      long rows,
      long occurrences,
      long distinctTargets,
      Map<String, Long> rowsByType,
      List<NamedCount> sample) {}

  record NamedCount(String target, long rows, long occurrences) {}

  record ToMethod(
      long evidenceWithCallee,
      long distinctCallees,
      long callsEvidence,
      long callsEvidenceWithoutCallee,
      int unnumberedCallees,
      long calleeOutsideTarget) {}

  record Externals(
      Share all,
      Share jdk,
      Share jdkDenylisted,
      Share jdkBeyondJava,
      Share notJdk,
      Share notJdkJavax,
      Share bare,
      Share bareLowerCase,
      List<NamedCount> bareLowerCaseSample,
      Share bareAllCaps,
      List<NamedCount> bareAllCapsSample,
      Share jdkOutsideImage,
      Map<String, Share> jdkOutsideImageByModule) {}

  record OwnPackageExternals(
      long rows,
      long occurrences,
      long distinctTargets,
      Map<String, Long> rowsByType,
      List<NamedCount> sample) {}

  record FanOutExternal(
      long types, Spread all, Spread withoutDenylisted, long pairs, long pairsWithoutDenylisted) {}

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
      long toMethodOnNonCalls,
      long toMethodNotInternalMethod,
      long weightMismatches,
      long externalRowsMalformed,
      long externalsWithoutDependency,
      long ambiguousMalformed,
      long externalQualityMismatches,
      boolean edgeTotalsMatchRows,
      boolean unresolvedCountMatchesRows,
      boolean classifiedCountMatchesRows,
      boolean ambiguousCountMatchesRows,
      List<String> baselineDifferences,
      boolean flushRuleReproduced,
      int elementOnlyAccepts,
      boolean writtenEqualsReadBack,
      boolean censusPassSameOutcomes,
      boolean writerDropsReproduced) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Drops(
      Map<String, Long> extractionByReason,
      long extractionTotal,
      int unresolvedDeclaredTarget,
      long pendingPeakRows,
      WriterDrops writer) {}

  record WriterDrops(
      long rows,
      long occurrences,
      Map<String, Long> rowsByType,
      Map<String, Long> occurrencesByType,
      Share intoParseFailedFile,
      int parseFailedFiles,
      Share nestedOfWrittenType,
      Share rest,
      List<ParseFailedFile> topParseFailedFiles) {}

  record Share(long rows, long occurrences) {}

  record ParseFailedFile(
      String path,
      String cause,
      long rows,
      long occurrences,
      long extendsRows,
      int topLevelTypes) {}

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
