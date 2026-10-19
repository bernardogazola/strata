package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Baseline;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Checks;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Drops;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Elements;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Flush;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Heap;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.RepositoryResult;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.SelectionResult;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Settings;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Timing;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Unit;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.UnitFunctions;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Write;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Environment;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.FileOutcomes;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Models;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.HeapPeakMeter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureCause;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitCensusPass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class DependencyExtractionSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final long NANOS_PER_MICRO = 1_000;
  private static final String REPORT = "benchmarks/dependency-extraction.json";
  private static final String BASELINE = "benchmarks/element-extraction.json";
  private static final String COMPLETED = "COMPLETED";
  private static final String OUT_OF_MEMORY = "OUT_OF_MEMORY";
  private static final String DERIVED = "broken-tree";
  private static final String DERIVED_FROM = "commons-lang";

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", DERIVED, "junit5", "spring-framework", "elasticsearch");

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final int SCHEMA_VERSION = 1;
  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final String MEASURE =
      "The analysis pipeline, wired by hand: RepositoryValidator, the selected roots in"
          + " MAIN, GENERATED, TEST order, a fresh cache, one RUNNING analysis, then"
          + " SpoonModelBuilder.analyze with a StructureExtractor over an ElementWriter; every row"
          + " is read back from code_element, dependency, dependency_evidence, module_dependency,"
          + " source_file and source_root after end(), edges are DependencyAggregator.aggregate"
          + " over the rows read back, and the writes are observed by a StructureSink that wraps"
          + " the ElementWriter and reads each table's max(id) after every accept. A second,"
          + " untimed pass over the same batches times the two per-unit functions and counts the"
          + " excluded constructs";

  private enum Selection {
    DEFAULT(
        EnumSet.of(SourceRootKind.MAIN, SourceRootKind.GENERATED),
        "MAIN and GENERATED, no TEST: the analysis default and spoon-corpus.json's population"),
    ALL(EnumSet.allOf(SourceRootKind.class), "every detected root");

    private final Set<SourceRootKind> kinds;
    private final String description;

    Selection(Set<SourceRootKind> kinds, String description) {
      this.kinds = kinds;
      this.description = description;
    }
  }

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  @Test
  void elementosEDependenciasReaisAtravessamOCorpusSemQuebrarUmInvariante() throws Exception {
    HermeticSystemReader.install();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final String baseCommit = baseCommit(strataRoot);
    final JsonNode baseline = new ObjectMapper().readTree(strataRoot.resolve(BASELINE).toFile());
    final Path corpus = corpusDirectory();
    final Path report = strataRoot.resolve(REPORT);
    final List<RepositoryResult> results = new ArrayList<>();

    for (final String name : REPOSITORIES) {
      final ValidatedRepository repository = validated(corpus.resolve(name));
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        final JsonNode expected = baselineOf(baseline, name, selection);
        selections.add(measure(name, repository, selection, expected));
        final List<RepositoryResult> measured = new ArrayList<>(results);
        measured.add(repositoryResult(name, repository, selections));
        write(report, reportOf(baseCommit, baseline, measured));
      }
      results.add(repositoryResult(name, repository, selections));
    }

    assertInvariants(results);
  }

  private SelectionResult measure(
      String name, ValidatedRepository repository, Selection selection, JsonNode expected)
      throws Exception {
    final List<SourceRoot> roots = roots(repository, selection);
    final Path data = temp.toRealPath().resolve(name).resolve(selection.name());
    final PersistenceRoot persistence = PersistenceRoot.prepare(data);
    final Measured run;
    final DependencyReadBack.Result readBack;
    final Path database;
    System.gc();
    try (RepositoryCache cache = open(persistence, repository)) {
      final SqliteConnections connections = cache.connections();
      database = connections.databaseFile();
      final long analysisPk = insertRunning(connections, repository, selection);
      final ElementWriter writer =
          new ElementWriter(
              connections, analysisPk, roots, repository.snapshot().inventory().javaSources());
      final MeasuringStructureSink sink = new MeasuringStructureSink(writer, connections, database);
      final StructureExtractor extractor = new StructureExtractor(roots, sink);
      final long started = System.nanoTime();
      try (HeapPeakMeter meter = new HeapPeakMeter()) {
        final SourceAnalysis analysis;
        try {
          analysis = new SpoonModelBuilder().analyze(repository, roots, extractor, () -> false);
        } catch (OutOfMemoryError exhausted) {
          final long wallMillis = millisSince(started);
          meter.drain();
          return outOfMemory(selection, roots, meter, wallMillis);
        }
        final long wallMillis = millisSince(started);
        meter.drain();
        run =
            new Measured(
                analysis,
                extractor,
                writer,
                sink,
                wallMillis,
                new Heap(
                    meter.peakLiveBytes(),
                    meter.peakUsedBytes(),
                    optionalLong(expected, "heap", "peakLiveBytes"),
                    optionalLong(expected, "heap", "peakUsedBytes")));
      }
      readBack = DependencyReadBack.read(connections, analysisPk);
    }
    final long cacheBytes = Files.size(database);
    TestCaches.deleteTree(data);
    final Census census = censusPass(repository, roots, readBack);
    return completed(selection, roots, run, readBack, cacheBytes, census, expected);
  }

  private static Census censusPass(
      ValidatedRepository repository, List<SourceRoot> roots, DependencyReadBack.Result readBack) {
    final DependencyReadBack.ExcludedPairs pairs =
        new DependencyReadBack.ExcludedPairs(readBack.census());
    final UnitCensusPass pass = new UnitCensusPass(roots, pairs);
    final long started = System.nanoTime();
    final SourceAnalysis analysis;
    try {
      analysis = new SpoonModelBuilder().analyze(repository, roots, pass, () -> false);
    } catch (OutOfMemoryError exhausted) {
      return new Census(null, null, null, millisSince(started));
    }
    final long wallMillis = millisSince(started);
    final UnitCensusPass.Result result = pass.result();
    return new Census(analysis, result, pairs, wallMillis);
  }

  private static SelectionResult completed(
      Selection selection,
      List<SourceRoot> roots,
      Measured run,
      DependencyReadBack.Result readBack,
      long cacheBytes,
      Census census,
      JsonNode expected) {
    final SourceAnalysis analysis = run.analysis();
    final SourceAnalysis.Diagnostics diagnostics = analysis.diagnostics();
    final MeasuringStructureSink sink = run.sink();
    final FileOutcomes files = files(analysis);
    final Write write = sink.write();
    final Drops drops = drops(run, write);
    return new SelectionResult(
        selection.name(),
        rootsByKind(roots),
        COMPLETED,
        files,
        readBack.elements(),
        readBack.dependencies(),
        readBack.evidence(),
        readBack.modules(),
        readBack.edges(),
        checks(readBack, sink, files, census, expected, write, drops),
        drops,
        census(
            SourceIssue.Code.values(),
            run.extractor().issues().stream().map(SourceIssue::code).toList()),
        write,
        new Timing(
            run.wallMillis(),
            diagnostics.preParseMillis(),
            diagnostics.buildMillis(),
            diagnostics.visitMillis(),
            diagnostics.visitMillis() - sink.millisInsideVisit()),
        unitFunctions(census),
        new Models(
            diagnostics.batches(),
            diagnostics.splitBatches(),
            diagnostics.quarantinedBatches(),
            diagnostics.builds()),
        run.heap(),
        cacheBytes,
        optionalLong(expected, "cacheBytes"),
        census.pairs() == null ? null : census.pairs().result(census.result()));
  }

  private static SelectionResult outOfMemory(
      Selection selection, List<SourceRoot> roots, HeapPeakMeter meter, long wallMillis) {
    return new SelectionResult(
        selection.name(),
        rootsByKind(roots),
        OUT_OF_MEMORY,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new Timing(wallMillis, 0, 0, 0, 0),
        null,
        null,
        new Heap(meter.peakLiveBytes(), meter.peakUsedBytes(), null, null),
        null,
        null,
        null);
  }

  private static Drops drops(Measured run, Write write) {
    final Map<String, Long> byReason = new LinkedHashMap<>();
    run.extractor()
        .droppedOccurrences()
        .forEach((reason, count) -> byReason.put(reason.name(), count));
    long pendingPeak = 0;
    for (final Flush flush : write.flushes()) {
      pendingPeak = Math.max(pendingPeak, flush.pendingAfter());
    }
    return new Drops(
        byReason,
        byReason.values().stream().mapToLong(Long::longValue).sum(),
        run.writer().droppedDependencies(),
        pendingPeak);
  }

  private static Checks checks(
      DependencyReadBack.Result readBack,
      MeasuringStructureSink sink,
      FileOutcomes files,
      Census census,
      JsonNode expected,
      Write write,
      Drops drops) {
    final DependencyReadBack.Integrity integrity = readBack.integrity();
    return new Checks(
        integrity.foreignKeyViolations(),
        integrity.danglingEndpoints(),
        integrity.sourcesNotInternalTypes(),
        integrity.targetsNotTypes(),
        integrity.moduleDependencyInDependency(),
        integrity.invalidResolutionPairs(),
        integrity.evidenceCountMismatches(),
        integrity.evidenceFileMismatches(),
        integrity.fromMethodOwnerMismatches(),
        integrity.evidenceWeakerThanRow(),
        integrity.toMethodsWritten(),
        integrity.weightMismatches(),
        integrity.externalRowsMalformed(),
        integrity.externalsWithoutDependency(),
        integrity.edgeTotalsMatchRows(),
        baselineDifferences(readBack.elements(), files, expected),
        sink.flushRuleReproduced(),
        sink.elementOnlyAccepts(),
        writtenEqualsReadBack(readBack, write, drops),
        census.analysis() != null && files.equals(files(census.analysis())));
  }

  private static boolean writtenEqualsReadBack(
      DependencyReadBack.Result readBack, Write write, Drops drops) {
    final List<Flush> transactions = new ArrayList<>(write.flushes());
    transactions.add(write.end());
    final Elements elements = readBack.elements();
    final long elementRows = transactions.stream().mapToLong(Flush::elementRows).sum();
    final long externals = transactions.stream().mapToLong(Flush::externalRowsWritten).sum();
    final long dependencies = transactions.stream().mapToLong(Flush::dependencyRowsWritten).sum();
    final long evidence = transactions.stream().mapToLong(Flush::evidenceRowsWritten).sum();
    final long modules = transactions.stream().mapToLong(Flush::moduleRowsWritten).sum();
    return elementRows == elements.packages() + elements.types() + elements.methods()
        && externals == elements.externalTypes()
        && dependencies == readBack.dependencies().rows()
        && evidence == readBack.evidence().rows()
        && modules == readBack.modules().rows()
        && write.end().pendingAfter() == drops.unresolvedDeclaredTarget();
  }

  private static List<String> baselineDifferences(
      Elements elements, FileOutcomes files, JsonNode expected) {
    final List<String> differences = new ArrayList<>();
    final JsonNode rows = expected.path("rows");
    compare(differences, "packages", elements.packages(), rows.path("packages"));
    compare(differences, "types", elements.types(), rows.path("types"));
    compare(differences, "methods", elements.methods(), rows.path("methods"));
    compare(differences, "files", elements.files(), rows.path("files"));
    compare(differences, "sourceFiles", elements.sourceFiles(), rows.path("sourceFiles"));
    compare(differences, "sourceRoots", elements.sourceRoots(), rows.path("sourceRoots"));
    elements
        .sourceFileStatuses()
        .forEach(
            (status, count) ->
                compare(
                    differences,
                    "sourceFileStatuses." + status,
                    count,
                    rows.path("sourceFileStatuses").path(status)));
    final JsonNode outcomes = expected.path("files");
    compare(differences, "files.total", files.total(), outcomes.path("total"));
    compare(differences, "files.parseFailed", files.parseFailed(), outcomes.path("parseFailed"));
    files
        .parseFailedByCause()
        .forEach(
            (cause, count) ->
                compare(
                    differences,
                    "files.parseFailedByCause." + cause,
                    count,
                    outcomes.path("parseFailedByCause").path(cause)));
    return differences;
  }

  private static void compare(
      List<String> differences, String field, long measured, JsonNode expected) {
    if (!expected.isNumber() || expected.asLong() != measured) {
      differences.add(field + ": " + measured + " (baseline " + expected + ")");
    }
  }

  private static UnitFunctions unitFunctions(Census census) {
    final UnitCensusPass.Result result = census.result();
    if (result == null) {
      return null;
    }
    final List<Unit> slowest =
        result.slowest().stream()
            .map(
                unit ->
                    new Unit(
                        unit.path(),
                        unit.totalMicros(),
                        unit.elementNanos() / NANOS_PER_MICRO,
                        unit.dependencyNanos() / NANOS_PER_MICRO))
            .toList();
    return new UnitFunctions(
        result.units(),
        result.elementNanos() / NANOS_PER_MILLI,
        result.dependencyNanos() / NANOS_PER_MILLI,
        (result.elementNanos() + result.dependencyNanos()) / NANOS_PER_MILLI,
        slowest.isEmpty() ? null : slowest.getFirst(),
        slowest,
        census.wallMillis());
  }

  private static void assertInvariants(List<RepositoryResult> results) {
    final SoftAssertions softly = new SoftAssertions();
    for (final RepositoryResult repository : results) {
      for (final SelectionResult measured : repository.selections()) {
        final String where = repository.name() + " " + measured.selection() + ": ";
        softly.assertThat(measured.outcome()).as(where + "dentro de -Xmx2g").isEqualTo(COMPLETED);
        if (!COMPLETED.equals(measured.outcome())) {
          continue;
        }
        final Checks checks = measured.checks();
        softly
            .assertThat(
                List.of(
                    checks.foreignKeyViolations(),
                    checks.danglingEndpoints(),
                    checks.sourcesNotInternalTypes(),
                    checks.targetsNotTypes(),
                    checks.moduleDependencyInDependency(),
                    checks.invalidResolutionPairs(),
                    checks.evidenceCountMismatches(),
                    checks.evidenceFileMismatches(),
                    checks.fromMethodOwnerMismatches(),
                    checks.evidenceWeakerThanRow(),
                    checks.toMethodsWritten(),
                    checks.weightMismatches(),
                    checks.externalRowsMalformed(),
                    checks.externalsWithoutDependency()))
            .as(where + "cada contagem de violação, na ordem de Checks")
            .containsOnly(0L);
        softly
            .assertThat(measured.dependencies().resolutionPairs())
            .as(where + "só os quatro pares (qualidade, is_external) válidos")
            .containsEntry("INFERRED,1", 0L)
            .containsEntry("UNRESOLVED_EXTERNAL,0", 0L);
        softly
            .assertThat(checks.baselineDifferences())
            .as(where + "os elementos e os desfechos de element-extraction.json")
            .isEmpty();
        softly.assertThat(checks.edgeTotalsMatchRows()).as(where + "arestas = linhas").isTrue();
        softly
            .assertThat(checks.flushRuleReproduced())
            .as(where + "as descargas observadas são as da regra do gravador")
            .isTrue();
        softly
            .assertThat(checks.elementOnlyAccepts())
            .as(where + "nenhum ElementSink.accept(BatchElements)")
            .isZero();
        softly
            .assertThat(checks.writtenEqualsReadBack())
            .as(where + "as transações observadas somam as linhas lidas, e o descarte é o pendente")
            .isTrue();
        softly
            .assertThat(checks.censusPassSameOutcomes())
            .as(where + "a passada do censo viu os mesmos desfechos")
            .isTrue();
        softly
            .assertThat(measured.extractionIssues())
            .as(where + "nenhum id acima de " + AnalysisConstants.MAX_ELEMENT_ID_BYTES + " bytes")
            .containsEntry(SourceIssue.Code.SOURCE_ELEMENT_ID_TOO_LONG.name(), 0);
        if (measured.excludedConstructs() != null) {
          softly
              .assertThat(measured.edges().toInternal())
              .as(where + "a base interna do censo é a das arestas")
              .isEqualTo(measured.excludedConstructs().baseInternalPairs());
        }
      }
    }
    softly.assertAll();
  }

  private static ValidatedRepository validated(Path directory) throws IOException {
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    assertThat(outcome)
        .as("o validador recusou " + directory.getFileName())
        .isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
  }

  private static List<SourceRoot> roots(ValidatedRepository repository, Selection selection) {
    return repository.layout().sourceRoots().stream()
        .filter(root -> selection.kinds.contains(root.kind()))
        .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
        .toList();
  }

  private RepositoryCache open(PersistenceRoot persistence, ValidatedRepository repository)
      throws CacheUnavailableException {
    return RepositoryCache.open(
            persistence,
            TestCaches.identityV01(),
            repository.snapshot().root(),
            ANALYZER_VERSION,
            clock)
        .cache();
  }

  private long insertRunning(
      SqliteConnections connections, ValidatedRepository repository, Selection selection)
      throws SQLException {
    final boolean includeTests = selection == Selection.ALL;
    return connections.write(
        c ->
            AnalysisDao.insertRunning(
                c,
                new NewAnalysis(
                    ANALYSIS_ID,
                    clock.millis(),
                    repository.snapshot().head().commitId(),
                    ANALYZER_VERSION,
                    SCHEMA_VERSION,
                    AnalysisConstants.PROTOCOL_VERSION,
                    "0".repeat(64),
                    "{\"includeTests\":" + includeTests + "}",
                    null,
                    includeTests,
                    HistoryScope.ALL,
                    null)));
  }

  private static FileOutcomes files(SourceAnalysis analysis) {
    int analyzed = 0;
    int tooLarge = 0;
    final List<ParseFailureCause> causes = new ArrayList<>();
    for (final SourceFile file : analysis.files()) {
      switch (file.status()) {
        case ANALYZED -> analyzed++;
        case SKIPPED_TOO_LARGE -> tooLarge++;
        case PARSE_FAILED -> causes.add(file.cause());
      }
    }
    return new FileOutcomes(
        analysis.totalFiles(),
        analyzed,
        tooLarge,
        analysis.parseFailures(),
        census(ParseFailureCause.values(), causes));
  }

  private static <E extends Enum<E>> Map<String, Integer> census(E[] all, List<E> seen) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final E value : all) {
      counts.put(value.name(), 0);
    }
    for (final E value : seen) {
      counts.merge(value.name(), 1, Integer::sum);
    }
    return counts;
  }

  private static Map<String, Integer> rootsByKind(List<SourceRoot> roots) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (final SourceRootKind kind : VISIT_ORDER) {
      counts.put(kind.name(), (int) roots.stream().filter(root -> root.kind() == kind).count());
    }
    return counts;
  }

  private static RepositoryResult repositoryResult(
      String name, ValidatedRepository repository, List<SelectionResult> selections) {
    final boolean derived = DERIVED.equals(name);
    return new RepositoryResult(
        name,
        derived ? "derived" : "clone",
        repository.snapshot().head().commitId(),
        derived ? DERIVED_FROM : null,
        rootsByKind(repository.layout().sourceRoots()),
        List.copyOf(selections));
  }

  private static DependencyExtractionReport reportOf(
      String baseCommit, JsonNode baseline, List<RepositoryResult> repositories) {
    final Map<String, String> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.description);
    }
    return new DependencyExtractionReport(
        SCHEMA,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        baseCommit,
        MEASURE,
        new Settings(
            System.getProperty("strata.spoon.version", "unknown"),
            AnalysisConstants.MAX_ELEMENT_ID_BYTES,
            AnalysisConstants.ELEMENT_FLUSH_ROWS,
            AnalysisConstants.ROWS_PER_INSERT,
            AnalysisConstants.DEPENDENCY_EVIDENCE_CAP,
            AnalysisConstants.MAX_MODEL_SOURCE_BYTES,
            String.join(", ", VISIT_ORDER.stream().map(SourceRootKind::name).toList()),
            selections,
            new Baseline(
                BASELINE,
                baseline.path("measuredAt").asText(),
                baseline.path("baseCommit").asText())),
        environment(),
        List.copyOf(repositories));
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

  private static Long optionalLong(JsonNode node, String... path) {
    JsonNode current = node;
    for (final String field : path) {
      current = current.path(field);
    }
    return current.isNumber() ? current.asLong() : null;
  }

  private static Environment environment() {
    final OperatingSystemMXBean system =
        ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    final List<String> flags =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(argument -> argument.startsWith("-X"))
            .toList();
    return new Environment(
        System.getProperty("os.name") + " " + System.getProperty("os.version"),
        System.getProperty("os.arch"),
        System.getProperty("strata.host.cpu", "not recorded: pass -Dstrata.host.cpu"),
        Runtime.getRuntime().availableProcessors(),
        system.getTotalMemorySize(),
        System.getProperty("strata.host.storage", "not recorded: pass -Dstrata.host.storage"),
        "full JDK under Maven Surefire, not the jlink image the application ships",
        System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
        flags,
        Runtime.getRuntime().maxMemory(),
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .toList());
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

  private static void write(Path report, DependencyExtractionReport content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
  }

  private record Measured(
      SourceAnalysis analysis,
      StructureExtractor extractor,
      ElementWriter writer,
      MeasuringStructureSink sink,
      long wallMillis,
      Heap heap) {}

  private record Census(
      SourceAnalysis analysis,
      UnitCensusPass.Result result,
      DependencyReadBack.ExcludedPairs pairs,
      long wallMillis) {}
}
