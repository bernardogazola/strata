package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Checks;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Duplicate;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.EndWrite;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Environment;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Failure;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.FileOutcomes;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Flush;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Heap;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Models;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.RepositoryResult;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Rows;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.SelectionResult;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Settings;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Timing;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.Write;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ElementSink;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.HeapPeakMeter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureCause;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
final class ElementExtractionSpikeIT {

  private static final int SCHEMA = 1;
  private static final int MAX_LISTED = 500;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final String REPORT = "benchmarks/element-extraction.json";
  private static final String COMPLETED = "COMPLETED";
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
          + " SpoonModelBuilder.analyze with a CodeElementExtractor over an ElementWriter; every"
          + " row is read back from code_element, source_file and source_root after end(), and"
          + " the writes are timed by an ElementSink that wraps the ElementWriter";

  private static final String ROWS_BY_KIND =
      "SELECT kind, count(*) FROM code_element WHERE analysis_pk = ? GROUP BY kind";

  private static final String FILE_STATUSES =
      "SELECT status, count(*) FROM source_file WHERE analysis_pk = ? GROUP BY status";

  private static final String SOURCE_ROOTS =
      "SELECT count(*) FROM source_root WHERE analysis_pk = ?";

  private static final String FILE_ROWS_JOINED =
      """
      SELECT count(*), count(DISTINCT f.id)
        FROM code_element e
        JOIN source_file f ON f.id = e.file_pk AND f.analysis_pk = e.analysis_pk
       WHERE e.analysis_pk = ? AND e.kind = 'FILE'""";

  private static final String FOREIGN_KEY_CHECK = "PRAGMA foreign_key_check";

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
  void oExtratorEOGravadorReaisAtravessamOCorpusSemQuebrarUmInvariante() throws Exception {
    HermeticSystemReader.install();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final String baseCommit = baseCommit(strataRoot);
    final Path corpus = corpusDirectory();
    final Path report = strataRoot.resolve(REPORT);
    final List<RepositoryResult> results = new ArrayList<>();

    for (final String name : REPOSITORIES) {
      final ValidatedRepository repository = validated(corpus.resolve(name));
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        selections.add(measure(name, repository, selection));
        final List<RepositoryResult> measured = new ArrayList<>(results);
        measured.add(repositoryResult(name, repository, selections));
        write(report, reportOf(baseCommit, measured));
      }
      results.add(repositoryResult(name, repository, selections));
    }

    assertInvariants(results);
  }

  private SelectionResult measure(String name, ValidatedRepository repository, Selection selection)
      throws Exception {
    final List<SourceRoot> roots = roots(repository, selection);
    final Path data = temp.toRealPath().resolve(name).resolve(selection.name());
    final PersistenceRoot persistence = PersistenceRoot.prepare(data);
    final Analyzed run;
    final ReadBack readBack;
    final Path database;
    System.gc();
    try (RepositoryCache cache = open(persistence, repository)) {
      final SqliteConnections connections = cache.connections();
      database = connections.databaseFile();
      final long analysisPk = insertRunning(connections, repository, selection);
      final MeasuringSink sink =
          new MeasuringSink(
              new ElementWriter(
                  connections, analysisPk, roots, repository.snapshot().inventory().javaSources()));
      final CodeElementExtractor extractor = new CodeElementExtractor(roots, sink);
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
            new Analyzed(
                analysis,
                extractor.issues(),
                sink,
                wallMillis,
                new Heap(meter.peakLiveBytes(), meter.peakUsedBytes()));
      }
      readBack = readBack(connections, analysisPk);
    }
    final long cacheBytes = Files.size(database);
    TestCaches.deleteTree(data);
    return completed(selection, roots, run, readBack, cacheBytes);
  }

  private static SelectionResult completed(
      Selection selection,
      List<SourceRoot> roots,
      Analyzed run,
      ReadBack readBack,
      long cacheBytes) {
    final SourceAnalysis analysis = run.analysis();
    final SourceAnalysis.Diagnostics diagnostics = analysis.diagnostics();
    final MeasuringSink sink = run.sink();
    return new SelectionResult(
        selection.name(),
        rootsByKind(roots),
        COMPLETED,
        files(analysis),
        readBack.rows(),
        new Checks(
            readBack.foreignKeyViolations(),
            readBack.fileRowsJoined(),
            readBack.distinctSourceFilesJoined(),
            sink.typesAccepted,
            sink.typeIds.size()),
        census(
            SourceIssue.Code.values(),
            run.extractionIssues().stream().map(SourceIssue::code).toList()),
        census(
            SourceIssue.Code.values(), analysis.issues().stream().map(SourceIssue::code).toList()),
        sink.write(),
        new Timing(
            run.wallMillis(),
            diagnostics.preParseMillis(),
            diagnostics.buildMillis(),
            diagnostics.visitMillis(),
            diagnostics.visitMillis() - sink.millisInsideVisit()),
        new Models(
            diagnostics.batches(),
            diagnostics.splitBatches(),
            diagnostics.quarantinedBatches(),
            diagnostics.builds()),
        cacheBytes,
        run.heap(),
        parseFailures(analysis, roots),
        duplicates(run.extractionIssues()));
  }

  private static SelectionResult outOfMemory(
      Selection selection, List<SourceRoot> roots, HeapPeakMeter meter, long wallMillis) {
    return new SelectionResult(
        selection.name(),
        rootsByKind(roots),
        "OUT_OF_MEMORY",
        null,
        null,
        null,
        null,
        null,
        null,
        new Timing(wallMillis, 0, 0, 0, 0),
        null,
        null,
        new Heap(meter.peakLiveBytes(), meter.peakUsedBytes()),
        null,
        null);
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
        final Rows rows = measured.rows();
        final Checks checks = measured.checks();
        softly
            .assertThat(checks.foreignKeyViolations())
            .as(where + "PRAGMA foreign_key_check")
            .isZero();
        softly
            .assertThat(
                List.of(rows.files(), checks.fileRowsJoined(), checks.distinctSourceFilesJoined()))
            .as(where + "cada linha FILE acha um source_file distinto, e há uma por source_file")
            .containsOnly(rows.sourceFiles());
        softly
            .assertThat(List.of(rows.types(), checks.typesAccepted()))
            .as(where + "uma linha TYPE por id de tipo distinto que a extração entregou")
            .containsOnly(checks.distinctTypeIdsAccepted());
        softly
            .assertThat(measured.extractionIssues())
            .as(where + "nenhum id acima de " + AnalysisConstants.MAX_ELEMENT_ID_BYTES + " bytes")
            .containsEntry(SourceIssue.Code.SOURCE_ELEMENT_ID_TOO_LONG.name(), 0);
        softly
            .assertThat(rows.sourceFileStatuses())
            .as(where + "os desfechos gravados são os finais da análise")
            .isEqualTo(statuses(measured.files()));
        softly
            .assertThat(measured.files().parseFailedByCause())
            .as(where + "nenhuma perda de modelo: a guarda do SpoonLaunchers a impede")
            .containsEntry(ParseFailureCause.EXTRACTION_CRASH.name(), 0);
        softly
            .assertThat(rows.packages() + rows.types() + rows.methods())
            .as(where + "as descargas contadas somam as linhas PACKAGE, TYPE e METHOD gravadas")
            .isEqualTo(writtenRows(measured.write()));
      }
    }
    softly.assertAll();
  }

  private static Map<String, Long> statuses(FileOutcomes files) {
    final Map<String, Long> statuses = new LinkedHashMap<>();
    statuses.put(SourceFileStatus.ANALYZED.name(), (long) files.analyzed());
    statuses.put(SourceFileStatus.SKIPPED_TOO_LARGE.name(), (long) files.tooLarge());
    statuses.put(SourceFileStatus.PARSE_FAILED.name(), (long) files.parseFailed());
    return statuses;
  }

  private static long writtenRows(Write write) {
    return write.flushes().stream().mapToLong(Flush::rows).sum() + write.end().rows();
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

  private static ReadBack readBack(SqliteConnections connections, long analysisPk)
      throws SQLException {
    return connections.read(
        c -> {
          final Map<String, Long> kinds = grouped(c, ROWS_BY_KIND, analysisPk);
          final Map<String, Long> found = grouped(c, FILE_STATUSES, analysisPk);
          final Map<String, Long> statuses = new LinkedHashMap<>();
          for (final SourceFileStatus status : SourceFileStatus.values()) {
            statuses.put(status.name(), found.getOrDefault(status.name(), 0L));
          }
          final long[] joined = longs(c, FILE_ROWS_JOINED, analysisPk, 2);
          final Rows rows =
              new Rows(
                  kinds.getOrDefault("PACKAGE", 0L),
                  kinds.getOrDefault("TYPE", 0L),
                  kinds.getOrDefault("METHOD", 0L),
                  kinds.getOrDefault("FILE", 0L),
                  statuses.values().stream().mapToLong(Long::longValue).sum(),
                  longs(c, SOURCE_ROOTS, analysisPk, 1)[0],
                  statuses);
          return new ReadBack(rows, foreignKeyViolations(c), joined[0], joined[1]);
        });
  }

  private static Map<String, Long> grouped(Connection connection, String sql, long analysisPk)
      throws SQLException {
    final Map<String, Long> counts = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          counts.put(rows.getString(1), rows.getLong(2));
        }
      }
    }
    return counts;
  }

  private static long[] longs(Connection connection, String sql, long analysisPk, int columns)
      throws SQLException {
    final long[] values = new long[columns];
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        for (int column = 0; column < columns; column++) {
          values[column] = rows.getLong(column + 1);
        }
      }
    }
    return values;
  }

  private static long foreignKeyViolations(Connection connection) throws SQLException {
    long violations = 0;
    try (PreparedStatement statement = connection.prepareStatement(FOREIGN_KEY_CHECK);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        violations++;
      }
    }
    return violations;
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

  private static List<Failure> parseFailures(SourceAnalysis analysis, List<SourceRoot> roots) {
    final Map<SourceRootId, SourceRootKind> kinds = new HashMap<>();
    roots.forEach(root -> kinds.put(root.id(), root.kind()));
    return analysis.files().stream()
        .filter(file -> file.status() == SourceFileStatus.PARSE_FAILED)
        .limit(MAX_LISTED)
        .map(
            file ->
                new Failure(
                    file.relativePath(), kinds.get(file.rootId()).name(), file.cause().name()))
        .toList();
  }

  private static List<Duplicate> duplicates(List<SourceIssue> extractionIssues) {
    return extractionIssues.stream()
        .filter(issue -> issue.code() == SourceIssue.Code.SOURCE_DUPLICATE_ELEMENT)
        .limit(MAX_LISTED)
        .map(
            issue ->
                new Duplicate(
                    (String) issue.params().get("filePath"),
                    (String) issue.params().get("firstFilePath")))
        .toList();
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

  private static ElementExtractionReport reportOf(
      String baseCommit, List<RepositoryResult> repositories) {
    final Map<String, String> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.description);
    }
    return new ElementExtractionReport(
        SCHEMA,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        baseCommit,
        MEASURE,
        new Settings(
            System.getProperty("strata.spoon.version", "unknown"),
            AnalysisConstants.MAX_ELEMENT_ID_BYTES,
            AnalysisConstants.ELEMENT_FLUSH_ROWS,
            AnalysisConstants.ROWS_PER_INSERT,
            AnalysisConstants.MAX_MODEL_SOURCE_BYTES,
            String.join(", ", VISIT_ORDER.stream().map(SourceRootKind::name).toList()),
            selections),
        environment(),
        List.copyOf(repositories));
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

  private static void write(Path report, ElementExtractionReport content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
  }

  private record Analyzed(
      SourceAnalysis analysis,
      List<SourceIssue> extractionIssues,
      MeasuringSink sink,
      long wallMillis,
      Heap heap) {}

  private record ReadBack(
      Rows rows, long foreignKeyViolations, long fileRowsJoined, long distinctSourceFilesJoined) {}

  private static final class MeasuringSink implements ElementSink {

    private final ElementSink writer;
    private final Set<CodeElementId> typeIds = new HashSet<>();
    private final List<Flush> flushes = new ArrayList<>();
    private long typesAccepted;
    private int acceptCalls;
    private int pendingBatches;
    private int pendingRows;
    private long beginNanos;
    private long acceptNanos;
    private long flushNanos;
    private long endNanos;
    private EndWrite end;

    private MeasuringSink(ElementSink writer) {
      this.writer = writer;
    }

    @Override
    public void begin(List<SourceFile> files) {
      final long started = System.nanoTime();
      writer.begin(files);
      beginNanos = System.nanoTime() - started;
    }

    @Override
    public void accept(BatchElements elements) {
      acceptCalls++;
      typesAccepted += elements.types().size();
      elements.types().forEach(type -> typeIds.add(type.id()));
      pendingBatches++;
      pendingRows += elements.size();
      final long started = System.nanoTime();
      writer.accept(elements);
      final long spent = System.nanoTime() - started;
      acceptNanos += spent;
      if (pendingRows >= AnalysisConstants.ELEMENT_FLUSH_ROWS) {
        flushes.add(new Flush(pendingBatches, pendingRows, spent / NANOS_PER_MILLI));
        flushNanos += spent;
        pendingBatches = 0;
        pendingRows = 0;
      }
    }

    @Override
    public void end(List<SourceFile> files, List<FileElement> fileElements) {
      final long started = System.nanoTime();
      writer.end(files, fileElements);
      endNanos = System.nanoTime() - started;
      end =
          new EndWrite(
              pendingBatches, pendingRows, fileElements.size(), endNanos / NANOS_PER_MILLI);
    }

    private long millisInsideVisit() {
      return (beginNanos + acceptNanos) / NANOS_PER_MILLI;
    }

    private Write write() {
      return new Write(
          beginNanos / NANOS_PER_MILLI,
          acceptCalls,
          List.copyOf(flushes),
          (acceptNanos - flushNanos) / NANOS_PER_MILLI,
          end,
          (beginNanos + acceptNanos + endNanos) / NANOS_PER_MILLI);
    }
  }
}
