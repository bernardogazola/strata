package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Baseline;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Cancellation;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Configuration;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Dependencies;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Elements;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Environment;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.FirstTick;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Gap;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Heap;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Host;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Models;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Peak;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.RepositoryResult;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.SelectionPayload;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Series;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Settings;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Size;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.StageResult;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Walk;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Warnings;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.WrittenException;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.ElementWriter;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositorySnapshot;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.RootCommits;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.HeapPeakMeter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionCounts;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.management.OperatingSystemMXBean;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class StagesBaselineSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;
  private static final long MICROS_PER_MILLI = 1_000L;
  private static final String REPORT = "benchmarks/stages-baseline.json";
  private static final String BASELINE = "benchmarks/code-metrics.json";
  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final String COLD = "COLD";
  private static final String COMPLETED = "analysis.completed";
  private static final int RUNS = 3;
  private static final String DERIVED = "broken-tree";
  private static final String DERIVED_FROM = "commons-lang";

  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);

  private static final long DEADLINE_MILLIS = 2_000;

  private static final long MODEL_LEVER_MILLIS = 400;

  private static final long REFERENCE_COMPILE_MILLIS = 1_576;

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", DERIVED, "junit5", "spring-framework", "elasticsearch");

  private static final Map<String, String> SIZE_CLASSES =
      Map.of(
          "commons-lang",
          "small",
          DERIVED,
          "small",
          "junit5",
          "small",
          "spring-framework",
          "medium",
          "elasticsearch",
          "large");

  private static final String MEASURE =
      "The production pipeline over the corpus: the real AnalysisRegistry built from"
          + " RegistrySettings.production with Stages.production(), one analysis.start per run on a"
          + " fresh data directory (COLD), events captured in process. Per stage, durationMs,"
          + " status and counters come from analysis.stage.completed and are cross-checked equal"
          + " to the analysis_stage rows; analysisWallMillis is analysis.started to the terminal"
          + " event by arrival time; bindMillis is the runner's own log line; firstTickMillis is"
          + " registry.start returning to the first analysis.progress frame (the in-process"
          + " analogue of the first-progress line of scripts/verify-analysis.sh, without the pipe"
          + " or a fresh JVM); heap from HeapPeakMeter"
          + " over the whole run; cacheBytes is the closed file. Elements, dependencies and"
          + " metric rows are SQL over the run's rows, against benchmarks/code-metrics.json. Each"
          + " selection runs one gap-census analysis first (an injected copy of SOURCE_ANALYSIS"
          + " wiring the same production classes, a timestamp-only BooleanSupplier in front of"
          + " the token), then the three timed runs.";

  private static final String FIRST_TICK =
      "registry.start returning -> the first analysis.progress frame reaching the sink, in"
          + " process: the analogue of what scripts/verify-analysis.sh measures over the"
          + " real pipe";

  private static final String GAP_CENSUS =
      "One extra analysis per selection, outside the medians: the production StagePlan with"
          + " SOURCE_ANALYSIS replaced by a copy that wires the same production classes"
          + " (ElementWriter with the cancellation supplier, StructureExtractor,"
          + " SourceProgressVisitor, SpoonModelBuilder.analyze) and puts a timestamp-only"
          + " BooleanSupplier in front of the stage's token. Every interval between two"
          + " consecutive checks is counted; stack samples of the analysis thread, the first 100 ms"
          + " into an open interval and then one every 250 ms, name its class (majority of"
          + " samples); the tail, last check ->"
          + " analyze returning, competes as an interval. prologueWalk is the same supplier"
          + " through RepositoryValidator.inspect and the first-parent root walk, once per"
          + " repository, outside the registry.";

  private static final Pattern ROOT_LINE =
      Pattern.compile("raiz de código '.*': (\\d+) modelo\\(s\\), (\\d+) build\\(s\\), \\d+ ms");

  private static final Pattern BIND_LINE =
      Pattern.compile("análise (an_[0-9A-Z]{26}) vinculada a repositoryKey=\\S+ bindMillis=(\\d+)");

  private static final ObjectMapper JSON = new ObjectMapper();

  private enum Selection {
    DEFAULT(false),
    ALL(true);

    private final boolean includeTests;

    Selection(boolean includeTests) {
      this.includeTests = includeTests;
    }

    AnalysisConfiguration configuration() {
      return AnalysisConfiguration.normalized(includeTests, HistoryScope.ALL, null, null, false);
    }

    SelectionPayload payload() {
      return new SelectionPayload(includeTests, HistoryScope.ALL.name(), null, null);
    }
  }

  private static final List<String> SOURCE_ANALYSIS_KEYS =
      List.of(
          "files",
          "types",
          "resolvedRefs",
          "inferredRefs",
          "unresolvedRefs",
          "ambiguousRefs",
          "dropLocalTarget",
          "dropInvalidName",
          "dropIdTooLong",
          "dropDefaultPackageCollision",
          "dropCallWithoutTarget",
          "droppedDeclaredTargets",
          "unnumberedCallees",
          "duplicateElements",
          "elementIdsTooLong");

  private record Expected(
      Completeness completeness,
      Map<String, Long> warnings,
      Map<String, Long> sourceAnalysis,
      Long builds) {}

  private record ExpectedLayout(
      long files, long javaFiles, long sourceRoots, long mainRoots, long testRoots) {}

  private static final String PARSE_FAILED = "SOURCE_PARSE_FAILED";
  private static final String DUPLICATE = "SOURCE_DUPLICATE_ELEMENT";
  private static final String SIBLING = AnalysisStage.GIT_HISTORY_CHEAP.name();

  private static final Map<String, ExpectedLayout> LAYOUTS =
      Map.of(
          "commons-lang",
          new ExpectedLayout(613, 527, 2, 1, 1),
          DERIVED,
          new ExpectedLayout(615, 528, 2, 1, 1),
          "junit5",
          new ExpectedLayout(2_319, 1_728, 45, 25, 20),
          "spring-framework",
          new ExpectedLayout(11_362, 9_210, 60, 30, 30),
          "elasticsearch",
          new ExpectedLayout(44_951, 30_841, 527, 292, 235));

  private static final Map<String, Expected> EXPECTED = expected();

  private static Map<String, Expected> expected() {
    final Map<String, Expected> table = new HashMap<>();
    table.put(
        key("commons-lang", Selection.DEFAULT),
        expect(
            259, 0, 0, true, Map.of(), null, 259, 364, 19_006, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0,
            0));
    table.put(
        key("commons-lang", Selection.ALL),
        expect(
            527, 0, 0, true, Map.of(), null, 527, 958, 36_113, 27_946, 30_752, 0, 6, 475, 0, 0,
            2_878, 0, 0, 0, 0));
    table.put(
        key(DERIVED, Selection.DEFAULT),
        expect(
            260,
            3,
            3,
            false,
            Map.of(PARSE_FAILED, 3L),
            null,
            260,
            362,
            18_728,
            0,
            6,
            0,
            0,
            64,
            0,
            0,
            5,
            0,
            0,
            0,
            0));
    table.put(
        key(DERIVED, Selection.ALL),
        expect(
            528,
            3,
            3,
            false,
            Map.of(PARSE_FAILED, 3L),
            null,
            528,
            956,
            35_835,
            27_297,
            31_407,
            0,
            6,
            475,
            0,
            0,
            2_883,
            0,
            0,
            0,
            0));
    table.put(
        key("junit5", Selection.DEFAULT),
        expect(
            914,
            0,
            0,
            true,
            Map.of(DUPLICATE, 1L),
            null,
            914,
            1_110,
            32_643,
            7_176,
            4_846,
            0,
            2,
            4,
            0,
            0,
            1_479,
            0,
            0,
            1,
            0));
    table.put(
        key("junit5", Selection.ALL),
        expect(
            1_712,
            0,
            0,
            true,
            Map.of(DUPLICATE, 4L),
            null,
            1_712,
            3_751,
            57_456,
            47_118,
            12_142,
            0,
            3,
            5,
            0,
            0,
            14_970,
            0,
            0,
            4,
            0));
    table.put(
        key("spring-framework", Selection.DEFAULT),
        expect(
            5_459, 0, 0, true, Map.of(), null, 5_459, 6_923, 221_960, 48_819, 43_272, 0, 5, 40, 0,
            0, 5_788, 0, 0, 0, 0));
    table.put(
        key("spring-framework", Selection.ALL),
        expect(
            9_202,
            0,
            0,
            true,
            Map.of(DUPLICATE, 7L),
            null,
            9_202,
            18_468,
            342_463,
            201_795,
            166_641,
            1,
            91,
            49,
            0,
            0,
            109_342,
            0,
            83,
            7,
            0));
    table.put(
        key("elasticsearch", Selection.DEFAULT),
        expect(
            16_848,
            1,
            1,
            true,
            Map.of(PARSE_FAILED, 1L, DUPLICATE, 2L),
            444L,
            16_848,
            26_883,
            1_017_540,
            437_256,
            116_824,
            0,
            62,
            95,
            0,
            0,
            68_617,
            9,
            15,
            2,
            0));
    table.put(
        key("elasticsearch", Selection.ALL),
        expect(
            26_782,
            3,
            3,
            true,
            Map.of(PARSE_FAILED, 3L, DUPLICATE, 6L),
            713L,
            26_782,
            39_364,
            1_846_308,
            1_117_426,
            374_817,
            0,
            453,
            205,
            0,
            0,
            331_737,
            21,
            27,
            6,
            0));
    return Map.copyOf(table);
  }

  private static Expected expect(
      int totalFiles,
      int skippedFiles,
      int parseFailures,
      boolean shallow,
      Map<String, Long> warnings,
      Long builds,
      long... sourceAnalysis) {
    final Map<String, Long> counters = new TreeMap<>();
    for (int i = 0; i < SOURCE_ANALYSIS_KEYS.size(); i++) {
      counters.put(SOURCE_ANALYSIS_KEYS.get(i), sourceAnalysis[i]);
    }
    return new Expected(
        new Completeness(totalFiles, skippedFiles, parseFailures, shallow, null),
        new TreeMap<>(warnings),
        counters,
        builds);
  }

  private static String key(String name, Selection selection) {
    return name + "/" + selection.name();
  }

  private static final String ELEMENTS_BY_KIND =
      "SELECT kind, is_external, count(*) FROM code_element WHERE analysis_pk = ?"
          + " GROUP BY kind, is_external";

  private static final String FILE_STATUSES =
      "SELECT status, count(*) FROM source_file WHERE analysis_pk = ? GROUP BY status";

  private static final String SOURCE_ROOTS =
      "SELECT count(*) FROM source_root WHERE analysis_pk = ?";

  private static final String DEPENDENCIES =
      """
      SELECT count(*), coalesce(sum(occurrences), 0),
             (SELECT count(*) FROM dependency_evidence v
                JOIN dependency d ON d.id = v.dependency_pk WHERE d.analysis_pk = ?1),
             (SELECT count(*) FROM module_dependency WHERE analysis_pk = ?1)
        FROM dependency WHERE analysis_pk = ?1""";

  private static final String METRIC_ROWS =
      """
      SELECT e.kind, e.is_external, count(*) FROM code_metric m
        JOIN code_element e ON e.id = m.element_pk
       WHERE m.analysis_pk = ? GROUP BY e.kind, e.is_external""";

  private static final String ANALYSIS_ROW =
      "SELECT status, config_hash, total_files, skipped_files, parse_failures, shallow_clone"
          + " FROM analysis WHERE id = ?";

  private static final String STAGE_ROWS =
      "SELECT stage, status, duration_ms, counters_json FROM analysis_stage WHERE analysis_pk = ?";

  @TempDir private Path temp;

  @Test
  void oPipelineDeProducaoMedidoSobreOCorpusEscreveALinhaDeBaseEMantemOPreRegistro()
      throws Exception {
    HermeticSystemReader.install();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final Environment environment = environment();
    final String baseCommit = baseCommit(strataRoot);
    final JsonNode baseline = JSON.readTree(strataRoot.resolve(BASELINE).toFile());
    final Path corpus = corpusDirectory();
    final Path report = strataRoot.resolve(REPORT);
    final List<RepositoryResult> results = new ArrayList<>();
    final List<Measured> measured = new ArrayList<>();
    final StderrTee stderr = StderrTee.install();
    try {
      for (final String name : REPOSITORIES) {
        final Path repository = corpus.resolve(name).toRealPath();
        RepositoryResult result = repositoryResult(name, repository);
        for (final Selection selection : Selection.values()) {
          final Measured selectionMeasured =
              measureSelection(
                  name, repository, selection, baselineOf(baseline, name, selection), stderr);
          measured.add(selectionMeasured);
          result = result.with(selectionMeasured.configuration());
          final List<RepositoryResult> partial = new ArrayList<>(results);
          partial.add(result);
          write(report, reportOf(baseCommit, baseline, environment, partial, measured));
        }
        results.add(result);
      }
    } finally {
      stderr.uninstall();
    }
    write(report, reportOf(baseCommit, baseline, environment, results, measured));
    assertPreRegistration(measured);
  }

  private Measured measureSelection(
      String name, Path repository, Selection selection, JsonNode expected, StderrTee stderr)
      throws Exception {
    final CensusSourceAnalysis census = new CensusSourceAnalysis();
    final RunMeasure censusRun =
        analyze(
            name,
            repository,
            selection,
            "census",
            Stages.production().with(AnalysisStage.SOURCE_ANALYSIS, census),
            stderr);
    final List<RunMeasure> timed = new ArrayList<>();
    for (int run = 1; run <= RUNS; run++) {
      timed.add(analyze(name, repository, selection, "run" + run, null, stderr));
    }
    final GapCensus.Result gaps = census.result();
    final RunMeasure first = timed.get(0);
    final List<String> differences = baselineDifferences(first.readBack(), expected);
    final Configuration configuration =
        new Configuration(
            selection.name(),
            first.readBack().configHash(),
            COLD,
            first.terminalEvent(),
            first.completeness(),
            new Warnings(first.warnings(), trueCounts(first)),
            first.models(),
            stages(timed),
            Series.of(timed.stream().map(RunMeasure::wallMillis).toList()),
            Series.of(timed.stream().map(RunMeasure::bindMillis).toList()),
            firstTick(timed),
            new Heap(
                Peak.of(timed.stream().map(RunMeasure::peakLiveBytes).toList()),
                Peak.of(timed.stream().map(RunMeasure::peakUsedBytes).toList())),
            Series.of(timed.stream().map(RunMeasure::cacheBytes).toList()),
            first.readBack().elements(),
            first.readBack().dependencies(),
            first.readBack().metricRows(),
            differences,
            cancellation(gaps, censusRun),
            timed.stream().map(run -> run.id().value()).toList());
    return new Measured(
        name, selection, configuration, censusRun, timed, gaps, census.diagnostics(), expected);
  }

  private RunMeasure analyze(
      String name,
      Path repository,
      Selection selection,
      String label,
      StagePlan plan,
      StderrTee stderr)
      throws Exception {
    final Path data = temp.toRealPath().resolve(name + "-" + selection.name() + "-" + label);
    final RecordingFrameSink sink = new RecordingFrameSink();
    final RegistrySettings production =
        RegistrySettings.production(PersistenceRoot.prepare(data), sink, ANALYZER_VERSION);
    final RegistrySettings settings = plan == null ? production : production.withPlan(plan);
    stderr.reset();
    System.gc();
    final AnalysisRegistry registry = new AnalysisRegistry(settings);
    final AnalysisId id;
    final long startReturned;
    final long peakLive;
    final long peakUsed;
    final ReadBack readBack;
    final Path database;
    try {
      try (HeapPeakMeter meter = new HeapPeakMeter()) {
        final StartResult result =
            registry.start(repository.toString(), repository, selection.configuration());
        startReturned = System.nanoTime();
        assertThat(result)
            .as("nenhuma análise roda neste registro novo")
            .isInstanceOf(StartResult.Started.class);
        final StartResult.Started started = (StartResult.Started) result;
        id = started.analysisId();
        started.launch().run();
        sink.awaitTerminal(id, RUN_TIMEOUT);
        meter.drain();
        peakLive = meter.peakLiveBytes();
        peakUsed = meter.peakUsedBytes();
      }
      RunFixtures.runThread(sink, id).join(RUN_TIMEOUT.toMillis());
      final ReadableAnalysis readable =
          registry
              .readable(id)
              .orElseThrow(() -> new AssertionError("a análise " + id + " não ficou legível"));
      database = readable.connections().databaseFile();
      readBack = readable.connections().read(c -> readBack(c, readable.analysisPk()));
    } finally {
      registry.close();
    }
    final long cacheBytes = Files.size(database);
    deleteTree(data);
    return RunMeasure.of(
        id, sink.of(id), startReturned, peakLive, peakUsed, readBack, cacheBytes, stderr.lines());
  }

  private static final class CensusSourceAnalysis implements StageExecutor {

    private final AtomicReference<GapCensus.Result> result = new AtomicReference<>();
    private final AtomicReference<SourceAnalysis.Diagnostics> diagnostics = new AtomicReference<>();

    GapCensus.Result result() {
      return Objects.requireNonNull(result.get(), "the census analysis did not run");
    }

    SourceAnalysis.Diagnostics diagnostics() {
      return diagnostics.get();
    }

    @Override
    public StageOutcome execute(StageContext context) throws SQLException {
      final RunInputs inputs = context.inputs();
      final List<SourceRoot> roots = inputs.selectedRoots();
      final long total = roots.stream().mapToLong(SourceRoot::javaFileCount).sum();
      final SourceAnalysis analysis;
      final StructureExtractor extractor;
      final ElementWriter writer;
      try (GapCensus gaps = new GapCensus(context.cancellation().asSupplier())) {
        gaps.begin();
        writer =
            new ElementWriter(
                context.connections(),
                context.analysisPk(),
                roots,
                inputs.repository().snapshot().inventory().javaSources(),
                gaps);
        extractor = new StructureExtractor(roots, writer);
        context.progress().report(ProgressUnit.JAVA_FILES, 0, total);
        analysis =
            new SpoonModelBuilder()
                .analyze(
                    inputs.repository(),
                    roots,
                    new SourceProgressVisitor(gaps.visiting(extractor), context.progress(), total),
                    gaps);
        gaps.end();
        result.set(gaps.result());
      }
      diagnostics.set(analysis.diagnostics());
      context.cancellation().throwIfCancelled();
      for (final SourceIssue issue : analysis.issues()) {
        context.warnings().warn(ErrorCodes.source(issue));
      }
      for (final SourceIssue issue : extractor.issues()) {
        context.warnings().warn(ErrorCodes.source(issue));
      }
      context
          .completeness()
          .recordSourceFiles(
              analysis.totalFiles(), analysis.skippedFiles(), analysis.parseFailures());
      final long types =
          context
              .connections()
              .read(connection -> ElementDao.countInternalTypes(connection, context.analysisPk()));
      return StageOutcome.completed(counters(analysis, extractor, writer, types));
    }

    private static StageCounters counters(
        SourceAnalysis analysis, StructureExtractor extractor, ElementWriter writer, long types) {
      final Map<String, Long> values = new LinkedHashMap<>();
      values.put("files", (long) analysis.totalFiles());
      values.put("types", types);
      final ResolutionCounts resolution = extractor.resolutionCounts();
      values.put("resolvedRefs", resolution.resolved());
      values.put("inferredRefs", resolution.inferred());
      values.put("unresolvedRefs", resolution.unresolved());
      values.put("ambiguousRefs", resolution.ambiguous());
      extractor
          .droppedOccurrences()
          .forEach(
              (reason, occurrences) ->
                  values.put(SourceAnalysisStage.dropKey(reason), occurrences));
      values.put("droppedDeclaredTargets", (long) writer.droppedDependencies());
      values.put("unnumberedCallees", (long) writer.unnumberedCallees());
      values.put("duplicateElements", extractor.duplicateElements());
      values.put("elementIdsTooLong", extractor.elementIdsTooLong());
      return StageCounters.of(values);
    }
  }

  private static Cancellation cancellation(GapCensus.Result gaps, RunMeasure censusRun) {
    final GapCensus.Classified longest = gaps.longest();
    return new Cancellation(
        gaps.longestMillis(),
        longest == null ? GapCensus.UNSAMPLED : longest.gapClass(),
        longest == null
            ? gaps.longestClosedBy()
            : Optional.ofNullable(longest.batch()).orElse(longest.closedBy()),
        gaps.over1000(),
        gaps.over2000(),
        gaps.tailMillis(),
        gaps.over250(),
        gaps.checks(),
        gaps.longestClosedBy(),
        censusRun.wallMillis(),
        gaps.top().stream().map(Gap::of).toList());
  }

  private static Walk prologueWalk(Path repository) {
    try (GapCensus walk = new GapCensus(() -> false)) {
      walk.begin();
      final long started = System.nanoTime();
      final RepositoryOutcome<RepositorySnapshot> outcome =
          new RepositoryValidator().inspect(repository, walk, seen -> {});
      assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
      final RepositorySnapshot snapshot =
          ((RepositoryOutcome.Accepted<RepositorySnapshot>) outcome).value();
      RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), walk);
      final long wallMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
      walk.end();
      final GapCensus.Result result = walk.result();
      return new Walk(result.checks(), result.longestMillis(), wallMillis);
    }
  }

  private static ReadBack readBack(Connection c, long analysisPk) throws SQLException {
    final Map<String, Object> analysis = row(c, ANALYSIS_ROW, analysisPk);
    final Map<String, StageRow> stages = new TreeMap<>();
    try (PreparedStatement statement = c.prepareStatement(STAGE_ROWS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          stages.put(
              rows.getString(1),
              new StageRow(rows.getString(2), rows.getLong(3), parseCounters(rows.getString(4))));
        }
      }
    }
    return new ReadBack(
        (String) analysis.get("status"),
        (String) analysis.get("config_hash"),
        new Completeness(
            ((Number) analysis.get("total_files")).intValue(),
            ((Number) analysis.get("skipped_files")).intValue(),
            ((Number) analysis.get("parse_failures")).intValue(),
            ((Number) analysis.get("shallow_clone")).intValue() == 1,
            null),
        stages,
        elements(c, analysisPk),
        dependencies(c, analysisPk),
        metricRows(c, analysisPk));
  }

  private static Elements elements(Connection c, long analysisPk) throws SQLException {
    final Map<String, Long> internal = new HashMap<>();
    long externalTypes = 0;
    try (PreparedStatement statement = c.prepareStatement(ELEMENTS_BY_KIND)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          if (rows.getInt(2) == 0) {
            internal.put(rows.getString(1), rows.getLong(3));
          } else {
            externalTypes += rows.getLong(3);
          }
        }
      }
    }
    final Map<String, Long> statuses = new LinkedHashMap<>();
    for (final SourceFileStatus status : SourceFileStatus.values()) {
      statuses.put(status.name(), 0L);
    }
    try (PreparedStatement statement = c.prepareStatement(FILE_STATUSES)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          statuses.put(rows.getString(1), rows.getLong(2));
        }
      }
    }
    return new Elements(
        internal.getOrDefault("PACKAGE", 0L),
        internal.getOrDefault("TYPE", 0L),
        internal.getOrDefault("METHOD", 0L),
        internal.getOrDefault("FILE", 0L),
        externalTypes,
        statuses.values().stream().mapToLong(Long::longValue).sum(),
        longs(c, SOURCE_ROOTS, analysisPk, 1)[0],
        statuses);
  }

  private static Dependencies dependencies(Connection c, long analysisPk) throws SQLException {
    final long[] values = longs(c, DEPENDENCIES, analysisPk, 4);
    return new Dependencies(values[0], values[1], values[2], values[3]);
  }

  private static Map<String, Long> metricRows(Connection c, long analysisPk) throws SQLException {
    final Map<String, Long> rows = new LinkedHashMap<>();
    for (final String kind : List.of("PACKAGE", "TYPE", "METHOD", "FILE", "external")) {
      rows.put(kind, 0L);
    }
    try (PreparedStatement statement = c.prepareStatement(METRIC_ROWS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.merge(
              result.getInt(2) == 0 ? result.getString(1) : "external",
              result.getLong(3),
              Long::sum);
        }
      }
    }
    return rows;
  }

  private static long[] longs(Connection c, String sql, long analysisPk, int columns)
      throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        final long[] values = new long[columns];
        for (int column = 0; column < columns; column++) {
          values[column] = result.getLong(column + 1);
        }
        return values;
      }
    }
  }

  private static Map<String, Object> row(Connection c, String sql, long analysisPk)
      throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).as("a linha analysis " + analysisPk).isTrue();
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
          values.put(result.getMetaData().getColumnLabel(column), result.getObject(column));
        }
        return values;
      }
    }
  }

  private static Map<String, Long> parseCounters(String json) {
    try {
      return new TreeMap<>(JSON.readValue(json, new TypeReference<Map<String, Long>>() {}));
    } catch (IOException malformed) {
      throw new AssertionError("counters_json não é um mapa plano de longs: " + json, malformed);
    }
  }

  private static void assertPreRegistration(List<Measured> measured) {
    final SoftAssertions softly = new SoftAssertions();
    GapCensus.Classified worst = null;
    String worstWhere = null;
    for (final Measured selection : measured) {
      final String where = selection.name() + " " + selection.selection() + ": ";
      final Expected expected = EXPECTED.get(key(selection.name(), selection.selection()));
      final ExpectedLayout layout = LAYOUTS.get(selection.name());
      final List<RunMeasure> every = new ArrayList<>(selection.timed());
      every.add(selection.census());
      for (final RunMeasure run : every) {
        final String at = where + run.id().value() + ": ";
        assertRun(softly, at, run, selection, expected, layout);
      }
      softly
          .assertThat(selection.census().models())
          .as(where + "os modelos e construções do log iguais aos do SourceAnalysis do censo")
          .isEqualTo(
              new Models(
                  selection.censusDiagnostics().batches(), selection.censusDiagnostics().builds()));
      final GapCensus.Result gaps = selection.gaps();
      final long longest = gaps.longestMillis();
      softly
          .assertThat(longest)
          .as(where + "o maior intervalo de cancelamento dentro do prazo")
          .isLessThanOrEqualTo(DEADLINE_MILLIS);
      softly.assertThat(gaps.over2000()).as(where + "intervalos acima de 2 000 ms").isZero();
      if (selection.selection() == Selection.ALL
          && (DERIVED.equals(selection.name()) || DERIVED_FROM.equals(selection.name()))) {
        softly
            .assertThat(longest)
            .as(where + "o passo MODEL do ValidateTest atrás do ouvinte")
            .isLessThanOrEqualTo(MODEL_LEVER_MILLIS);
      }
      if (gaps.longest() != null && (worst == null || gaps.longest().nanos() > worst.nanos())) {
        worst = gaps.longest();
        worstWhere = where;
      }
    }
    softly
        .assertThat(worst == null ? null : worst.gapClass())
        .as("o pior intervalo do corpus é o passo COMPILE do ECJ, em " + worstWhere + worst)
        .isEqualTo(GapCensus.COMPILE);
    softly.assertAll();
  }

  private static void assertRun(
      SoftAssertions softly,
      String at,
      RunMeasure run,
      Measured selection,
      Expected expected,
      ExpectedLayout layout) {
    softly.assertThat(run.terminalEvent()).as(at + "o evento terminal").isEqualTo(COMPLETED);
    softly.assertThat(run.readBack().status()).as(at + "a linha analysis").isEqualTo("COMPLETED");
    final List<String> order = run.stageEvents().stream().map(StageEvent::stage).toList();
    softly
        .assertThat(order)
        .as(at + "um stage.completed por estágio")
        .containsExactlyInAnyOrder(
            Stream.of(AnalysisStage.values()).map(AnalysisStage::name).toArray(String[]::new));
    softly
        .assertThat(order.stream().filter(stage -> !SIBLING.equals(stage)).toList())
        .as(at + "os outros nove na ordem do pipeline")
        .containsExactly(
            Stream.of(AnalysisStage.values())
                .map(AnalysisStage::name)
                .filter(stage -> !SIBLING.equals(stage))
                .toArray(String[]::new));
    for (final StageEvent event : run.stageEvents()) {
      final StageRow row = run.readBack().stages().get(event.stage());
      softly
          .assertThat(row)
          .as(at + event.stage() + ": a linha analysis_stage igual ao evento")
          .isEqualTo(new StageRow(event.status(), event.durationMs(), event.counters()));
      softly
          .assertThat(event.status())
          .as(at + event.stage() + ": o status")
          .isEqualTo(expectedStatus(event.stage()));
    }
    final Map<String, Map<String, Long>> counters = run.countersByStage();
    softly
        .assertThat(counters.get(AnalysisStage.REPOSITORY_VALIDATION.name()))
        .as(at + "os contadores do REPOSITORY_VALIDATION")
        .isEqualTo(
            new TreeMap<>(
                Map.of("files", layout.files(), "javaFiles", layout.javaFiles(), "warnings", 0L)));
    softly
        .assertThat(counters.get(AnalysisStage.LAYOUT_DISCOVERY.name()))
        .as(at + "os contadores do LAYOUT_DISCOVERY")
        .isEqualTo(
            new TreeMap<>(
                Map.of(
                    "sourceRoots", layout.sourceRoots(),
                    "mainRoots", layout.mainRoots(),
                    "generatedRoots", 0L,
                    "testRoots", layout.testRoots(),
                    "selectedRoots",
                        selection.selection() == Selection.ALL
                            ? layout.sourceRoots()
                            : layout.mainRoots())));
    softly
        .assertThat(counters.get(AnalysisStage.SOURCE_ANALYSIS.name()))
        .as(at + "os contadores do SOURCE_ANALYSIS")
        .isEqualTo(expected.sourceAnalysis());
    for (final AnalysisStage stage : AnalysisStage.values()) {
      if (stage.ordinal() > AnalysisStage.SOURCE_ANALYSIS.ordinal()) {
        softly.assertThat(counters.get(stage.name())).as(at + stage + ": {}").isEmpty();
      }
    }
    softly
        .assertThat(run.completeness())
        .as(at + "a completude")
        .isEqualTo(expected.completeness());
    softly
        .assertThat(run.readBack().completeness())
        .as(at + "a completude gravada")
        .isEqualTo(expected.completeness());
    softly
        .assertThat(run.warnings())
        .as(at + "os avisos emitidos por código")
        .isEqualTo(expected.warnings());
    softly
        .assertThat(baselineDifferences(run.readBack(), selection.baseline()))
        .as(at + "elementos, dependências e métricas de code-metrics.json")
        .isEmpty();
    softly
        .assertThat(run.models().builds())
        .as(at + "as construções")
        .isEqualTo(expected.builds() == null ? run.models().batches() : expected.builds());
  }

  private static String expectedStatus(String stage) {
    return switch (AnalysisStage.valueOf(stage)) {
      case REPOSITORY_VALIDATION, LAYOUT_DISCOVERY, SOURCE_ANALYSIS, PERSISTENCE -> "COMPLETED";
      case GIT_HISTORY_CHEAP,
          STRUCTURAL_GRAPH,
          GIT_HISTORY_DETAILED,
          EVOLUTIONARY_COUPLING,
          HOTSPOT_PROFILES,
          GUIDED_TOUR ->
          "SKIPPED";
    };
  }

  private static List<String> baselineDifferences(ReadBack readBack, JsonNode expected) {
    final List<String> differences = new ArrayList<>();
    final JsonNode elements = expected.path("elements");
    final Elements measured = readBack.elements();
    compare(differences, "elements.packages", measured.packages(), elements.path("packages"));
    compare(differences, "elements.types", measured.types(), elements.path("types"));
    compare(differences, "elements.methods", measured.methods(), elements.path("methods"));
    compare(differences, "elements.files", measured.files(), elements.path("files"));
    compare(
        differences,
        "elements.externalTypes",
        measured.externalTypes(),
        elements.path("externalTypes"));
    compare(
        differences, "elements.sourceFiles", measured.sourceFiles(), elements.path("sourceFiles"));
    compare(
        differences, "elements.sourceRoots", measured.sourceRoots(), elements.path("sourceRoots"));
    measured
        .sourceFileStatuses()
        .forEach(
            (status, count) ->
                compare(
                    differences,
                    "elements.sourceFileStatuses." + status,
                    count,
                    elements.path("sourceFileStatuses").path(status)));
    final JsonNode dependencies = expected.path("dependencies");
    final Dependencies rows = readBack.dependencies();
    compare(differences, "dependencies.rows", rows.rows(), dependencies.path("rows"));
    compare(
        differences,
        "dependencies.occurrences",
        rows.occurrences(),
        dependencies.path("occurrences"));
    compare(
        differences,
        "dependencies.evidenceRows",
        rows.evidenceRows(),
        dependencies.path("evidenceRows"));
    compare(
        differences, "dependencies.moduleRows", rows.moduleRows(), dependencies.path("moduleRows"));
    readBack
        .metricRows()
        .forEach(
            (kind, count) ->
                compare(
                    differences,
                    "metricRows." + kind,
                    count,
                    expected.path("metricRows").path(kind)));
    return differences;
  }

  private static void compare(
      List<String> differences, String field, long measured, JsonNode expected) {
    if (!expected.isNumber() || expected.asLong() != measured) {
      differences.add(field + ": " + measured + " (baseline " + expected + ")");
    }
  }

  private static List<StageResult> stages(List<RunMeasure> timed) {
    final List<StageResult> stages = new ArrayList<>();
    final RunMeasure first = timed.get(0);
    final List<StageEvent> inPipelineOrder =
        first.stageEvents().stream()
            .sorted(Comparator.comparing(event -> AnalysisStage.valueOf(event.stage())))
            .toList();
    for (final StageEvent event : inPipelineOrder) {
      final List<Long> durations = new ArrayList<>();
      for (final RunMeasure run : timed) {
        durations.add(
            run.stageEvents().stream()
                .filter(other -> other.stage().equals(event.stage()))
                .findFirst()
                .map(StageEvent::durationMs)
                .orElse(-1L));
      }
      stages.add(
          new StageResult(event.stage(), event.status(), event.counters(), Series.of(durations)));
    }
    return stages;
  }

  private static FirstTick firstTick(List<RunMeasure> timed) {
    final List<Long> micros = timed.stream().map(RunMeasure::firstTickMicros).toList();
    final Series series =
        Series.of(micros.stream().map(value -> value / MICROS_PER_MILLI).toList());
    return new FirstTick(series.median(), series.runs(), timed.get(0).firstTickStage(), micros);
  }

  private static Map<String, Long> trueCounts(RunMeasure run) {
    final Map<String, Long> sourceAnalysis =
        run.countersByStage().get(AnalysisStage.SOURCE_ANALYSIS.name());
    final Map<String, Long> total = new TreeMap<>();
    total.put(PARSE_FAILED, (long) run.completeness().parseFailures());
    total.put(
        "SOURCE_FILE_TOO_LARGE",
        (long) (run.completeness().skippedFiles() - run.completeness().parseFailures()));
    total.put(DUPLICATE, sourceAnalysis.getOrDefault("duplicateElements", -1L));
    total.put("SOURCE_ELEMENT_ID_TOO_LONG", sourceAnalysis.getOrDefault("elementIdsTooLong", -1L));
    return total;
  }

  private static StagesBaselineReport reportOf(
      String baseCommit,
      JsonNode baseline,
      Environment environment,
      List<RepositoryResult> repositories,
      List<Measured> measured) {
    final Map<String, SelectionPayload> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.payload());
    }
    return new StagesBaselineReport(
        SCHEMA,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        baseCommit,
        MEASURE,
        new Settings(
            System.getProperty("strata.spoon.version", "unknown"),
            "MAIN, GENERATED, TEST",
            selections,
            List.of(COLD),
            RUNS,
            "median",
            AnalysisConstants.PROGRESS_THROTTLE_MILLIS,
            (int) DEADLINE_MILLIS,
            productionCpuPoolSize(),
            "StagesBaselineSpikeIT (-Pspike)",
            new Baseline(
                BASELINE,
                baseline.path("measuredAt").asText(),
                baseline.path("baseCommit").asText()),
            FIRST_TICK,
            GAP_CENSUS),
        environment,
        writtenException(measured),
        List.copyOf(repositories));
  }

  private static List<WrittenException> writtenException(List<Measured> measured) {
    GapCensus.Classified bound = null;
    Measured where = null;
    for (final Measured selection : measured) {
      final GapCensus.Classified compile = selection.gaps().longestCompile();
      if (compile != null && (bound == null || compile.nanos() > bound.nanos())) {
        bound = compile;
        where = selection;
      }
    }
    if (bound == null) {
      return List.of();
    }
    return List.of(
        new WrittenException(
            GapCensus.COMPILE,
            "ECJ's COMPILE step of one compilation unit (InferenceContext18 inside"
                + " CompilationUnitDeclaration.resolve) has no callback: neither the SpoonProgress"
                + " nor the model-change listener fires inside it",
            bound.millis(),
            where.name(),
            where.selection().name(),
            bound.batch(),
            REFERENCE_COMPILE_MILLIS,
            (int) DEADLINE_MILLIS));
  }

  private static int productionCpuPoolSize() {
    final int spare = Runtime.getRuntime().availableProcessors() - 1;
    return Math.max(1, Math.min(spare, AnalysisConstants.CPU_POOL_MAX_THREADS));
  }

  private static RepositoryResult repositoryResult(String name, Path repository)
      throws IOException {
    final boolean derived = DERIVED.equals(name);
    final ExpectedLayout layout = LAYOUTS.get(name);
    final RepositoryOutcome<RepositorySnapshot> outcome =
        new RepositoryValidator().inspect(repository);
    assertThat(outcome)
        .as("o validador recusou " + name)
        .isInstanceOf(RepositoryOutcome.Accepted.class);
    final RepositorySnapshot snapshot =
        ((RepositoryOutcome.Accepted<RepositorySnapshot>) outcome).value();
    return new RepositoryResult(
        name,
        derived ? "derived" : "clone",
        snapshot.head().commitId(),
        derived ? DERIVED_FROM : null,
        SIZE_CLASSES.get(name),
        snapshot.head().shallow(),
        commitCount(repository),
        new Size(snapshot.inventory().totalFileCount(), snapshot.inventory().javaSources().size()),
        prologueWalk(repository),
        List.of());
  }

  private static long commitCount(Path repository) throws IOException {
    final File root = repository.toFile();
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder().addCeilingDirectory(root.getParentFile()).findGitDir(root);
    try (Repository git = builder.setMustExist(true).build();
        RevWalk walk = new RevWalk(git)) {
      final ObjectId head = git.resolve("HEAD");
      if (head == null) {
        return 0;
      }
      walk.setRetainBody(false);
      walk.markStart(walk.parseCommit(head));
      long commits = 0;
      for (final RevCommit ignored : walk) {
        commits++;
      }
      return commits;
    }
  }

  private static Environment environment() {
    final String cpu = System.getProperty("strata.host.cpu");
    final String storage = System.getProperty("strata.host.storage");
    assertThat(cpu)
        .as("o descritor de máquina é obrigatório: passe -Dstrata.host.cpu")
        .isNotBlank();
    assertThat(storage)
        .as("o descritor de máquina é obrigatório: passe -Dstrata.host.storage")
        .isNotBlank();
    final OperatingSystemMXBean system =
        ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    final List<String> flags =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(
                argument -> argument.startsWith("-X") || argument.startsWith("-Djava.awt.headless"))
            .toList();
    final String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
    final String jvm =
        System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version");
    final int cores = Runtime.getRuntime().availableProcessors();
    return new Environment(
        os,
        System.getProperty("os.arch"),
        cpu,
        cores,
        system.getTotalMemorySize(),
        storage,
        "full JDK under Maven Surefire, not the jlink image the application ships",
        jvm,
        flags,
        Runtime.getRuntime().maxMemory(),
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .toList(),
        assertsRun(),
        new Host(
            os,
            System.getProperty("os.arch"),
            cpu,
            cores,
            system.getTotalMemorySize(),
            storage,
            jvm));
  }

  private static boolean assertsRun() {
    boolean enabled = false;
    assert enabled = true;
    return enabled;
  }

  private static JsonNode baselineOf(JsonNode baseline, String name, Selection selection) {
    for (final JsonNode repository : baseline.path("repositories")) {
      if (!name.equals(repository.path("name").asText())) {
        continue;
      }
      for (final JsonNode measured : repository.path("selections")) {
        if (selection.name().equals(measured.path("selection").asText())) {
          return measured;
        }
      }
    }
    throw new IllegalStateException(BASELINE + " não tem " + name + " " + selection);
  }

  private static Path corpusDirectory() throws IOException {
    final Path corpus = Path.of(System.getProperty("strata.corpus"));
    assertThat(corpus.resolve(DERIVED))
        .as("o corpus não foi clonado: rode bash scripts/corpus.sh antes do spike")
        .isDirectory();
    return corpus.toRealPath();
  }

  private static String baseCommit(Path strataRoot) throws IOException {
    final File root = strataRoot.toFile();
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder().addCeilingDirectory(root.getParentFile()).findGitDir(root);
    assertThat(builder.getGitDir())
        .as("strata.root não é um checkout Git do Strata: " + root)
        .isNotNull();
    try (Repository strata = builder.setMustExist(true).build()) {
      return Optional.ofNullable(strata.resolve("HEAD"))
          .map(head -> head.abbreviate(7).name())
          .orElse("unborn");
    }
  }

  private static void write(Path report, StagesBaselineReport content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private record Measured(
      String name,
      Selection selection,
      Configuration configuration,
      RunMeasure census,
      List<RunMeasure> timed,
      GapCensus.Result gaps,
      SourceAnalysis.Diagnostics censusDiagnostics,
      JsonNode baseline) {}

  private record StageRow(String status, long durationMs, Map<String, Long> counters) {}

  private record StageEvent(
      String stage, String status, long durationMs, Map<String, Long> counters) {}

  private record ReadBack(
      String status,
      String configHash,
      Completeness completeness,
      Map<String, StageRow> stages,
      Elements elements,
      Dependencies dependencies,
      Map<String, Long> metricRows) {}

  private record RunMeasure(
      AnalysisId id,
      String terminalEvent,
      Completeness completeness,
      List<StageEvent> stageEvents,
      Map<String, Long> warnings,
      long wallMillis,
      long firstTickMicros,
      String firstTickStage,
      long bindMillis,
      Models models,
      long peakLiveBytes,
      long peakUsedBytes,
      ReadBack readBack,
      long cacheBytes) {

    static RunMeasure of(
        AnalysisId id,
        List<Frame> frames,
        long startReturned,
        long peakLive,
        long peakUsed,
        ReadBack readBack,
        long cacheBytes,
        List<String> stderr) {
      final Frame started = only(frames, "analysis.started");
      final Frame terminal =
          frames.stream()
              .filter(Frame::isTerminal)
              .findFirst()
              .orElseThrow(() -> new AssertionError(id + " não tem evento terminal"));
      final Frame firstTick =
          frames.stream()
              .filter(frame -> "analysis.progress".equals(frame.event()))
              .findFirst()
              .orElseThrow(() -> new AssertionError(id + " não tem analysis.progress"));
      final List<StageEvent> stages = new ArrayList<>();
      final Map<String, Long> warnings = new TreeMap<>();
      for (final Frame frame : frames) {
        if ("analysis.stage.completed".equals(frame.event())) {
          stages.add(
              new StageEvent(
                  frame.stage(),
                  frame.payload().path("status").asText(),
                  frame.payload().path("durationMs").asLong(),
                  JSON.convertValue(
                      frame.payload().path("counters"),
                      new TypeReference<TreeMap<String, Long>>() {})));
        } else if ("analysis.warning".equals(frame.event())) {
          warnings.merge(frame.payload().path("error").path("code").asText(), 1L, Long::sum);
        }
      }
      final JsonNode completeness = terminal.payload().path("completeness");
      return new RunMeasure(
          id,
          terminal.event(),
          JSON.convertValue(completeness, Completeness.class),
          List.copyOf(stages),
          warnings,
          (terminal.nanos() - started.nanos()) / NANOS_PER_MILLI,
          (firstTick.nanos() - startReturned) / NANOS_PER_MICRO,
          firstTick.stage(),
          bindMillis(id, stderr),
          models(stderr),
          peakLive,
          peakUsed,
          readBack,
          cacheBytes);
    }

    Map<String, Map<String, Long>> countersByStage() {
      final Map<String, Map<String, Long>> byStage = new LinkedHashMap<>();
      for (final StageEvent event : stageEvents) {
        byStage.put(event.stage(), event.counters());
      }
      return byStage;
    }

    private static Frame only(List<Frame> frames, String event) {
      final List<Frame> found =
          frames.stream().filter(frame -> event.equals(frame.event())).toList();
      assertThat(found).as("exatamente um " + event).hasSize(1);
      return found.get(0);
    }

    private static long bindMillis(AnalysisId id, List<String> stderr) {
      for (final String line : stderr) {
        final Matcher bind = BIND_LINE.matcher(line);
        if (bind.find() && bind.group(1).equals(id.value())) {
          return Long.parseLong(bind.group(2));
        }
      }
      return -1;
    }

    private static Models models(List<String> stderr) {
      long batches = 0;
      long builds = 0;
      for (final String line : stderr) {
        final Matcher root = ROOT_LINE.matcher(line);
        if (root.find()) {
          batches += Long.parseLong(root.group(1));
          builds += Long.parseLong(root.group(2));
        }
      }
      return new Models(batches, builds);
    }
  }

  private static final class StderrTee extends OutputStream {

    private final PrintStream original;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final List<String> lines = new ArrayList<>();

    private StderrTee(PrintStream original) {
      this.original = original;
    }

    static StderrTee install() {
      final StderrTee tee = new StderrTee(System.err);
      System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
      return tee;
    }

    void uninstall() {
      System.setErr(original);
    }

    synchronized void reset() {
      lines.clear();
      line.reset();
    }

    synchronized List<String> lines() {
      return List.copyOf(lines);
    }

    @Override
    public synchronized void write(int b) {
      original.write(b);
      capture(b);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      original.write(bytes, offset, length);
      for (int i = offset; i < offset + length; i++) {
        capture(bytes[i]);
      }
    }

    private void capture(int b) {
      if (b != '\n') {
        line.write(b);
        return;
      }
      final String complete = line.toString(StandardCharsets.UTF_8);
      if (complete.contains("bindMillis=") || complete.contains("raiz de código '")) {
        lines.add(complete);
      }
      line.reset();
    }

    @Override
    public void flush() {
      original.flush();
    }
  }
}
