package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Elements;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Environment;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Heap;
import br.pucpr.tcc.strata.analyzer.persistence.ElementExtractionReport.FileOutcomes;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchStructure;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.HeapPeakMeter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureCause;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceCharsets;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureSink;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
final class CodeMetricsSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final String REPORT = "benchmarks/code-metrics.json";
  private static final String BASELINE = "benchmarks/dependency-extraction.json";
  private static final String DUMPS = "analyzer/target/code-metrics-spike";
  private static final String COMPLETED = "COMPLETED";
  private static final String OUT_OF_MEMORY = "OUT_OF_MEMORY";
  private static final String DERIVED = "broken-tree";
  private static final String DERIVED_FROM = "commons-lang";

  private static final long METRIC_BUDGET_MILLIS = 5_000;

  private static final String BUDGET_REPOSITORY = "elasticsearch";

  private static final long HEAP_TOLERANCE_BYTES = 64L * 1024 * 1024;

  private static final String DETERMINISM_REPOSITORY = "commons-lang";
  private static final int TOP_TYPES = 10;
  private static final int SAMPLES = 5;

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", DERIVED, "junit5", "spring-framework", "elasticsearch");

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final int SCHEMA_VERSION = 1;
  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final String MEASURE =
      "The analysis pipeline, wired by hand, with the code metrics:"
          + " RepositoryValidator, the selected roots in MAIN, GENERATED, TEST order, a fresh"
          + " cache, one RUNNING analysis, then SpoonModelBuilder.analyze with a StructureExtractor"
          + " over an ElementWriter that a delegating sink only times; every number is SQL over"
          + " the rows read back after"
          + " end(), against benchmarks/dependency-extraction.json for the same repository and"
          + " selection. The LOC and CC times are StructureExtractor.metricTime(); the FILE loc,"
          + " which the pre-parse computes outside that timer, is recounted after the analysis,"
          + " outside every clock and the heap meter, over the same bytes. commons-lang ALL is"
          + " measured a second time at the end and its two code_metric dumps compared byte for"
          + " byte.";

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

  private static final Map<String, String> VIOLATIONS = violations();

  private static final String FILE_ROWS =
      """
      SELECT f.relative_path, m.element_pk IS NOT NULL, m.loc FROM code_element e
        JOIN source_file f ON f.id = e.file_pk
        LEFT JOIN code_metric m ON m.element_pk = e.id
       WHERE e.analysis_pk = ? AND e.kind = 'FILE'""";

  private static final String WITHOUT_CC =
      """
      SELECT CASE WHEN e.start_line IS NULL THEN 'implicit'
                  WHEN (' ' || e.modifiers || ' ') LIKE '% abstract %' THEN 'abstract'
                  WHEN (' ' || e.modifiers || ' ') LIKE '% native %' THEN 'native'
                  WHEN t.type_kind IN ('INTERFACE', 'ANNOTATION') THEN 'interfaceOrAnnotation'
                  ELSE 'other' END, count(*)
        FROM code_metric m JOIN code_element e ON e.id = m.element_pk
        LEFT JOIN code_element t ON t.id = e.parent_pk
       WHERE m.analysis_pk = ? AND e.kind = 'METHOD' AND m.unit_count = 0 GROUP BY 1""";

  private static final String WITHOUT_CC_OTHER =
      """
      SELECT e.element_id || ' @ ' || coalesce(f.relative_path, '?') || ':' || e.start_line
             || '-' || e.end_line || ' [' || e.modifiers || '] in ' || coalesce(t.type_kind, '?')
        FROM code_metric m JOIN code_element e ON e.id = m.element_pk
        LEFT JOIN code_element t ON t.id = e.parent_pk
        LEFT JOIN source_file f ON f.id = e.file_pk
       WHERE m.analysis_pk = ? AND e.kind = 'METHOD' AND m.unit_count = 0
         AND e.start_line IS NOT NULL
         AND (' ' || e.modifiers || ' ') NOT LIKE '% abstract %'
         AND (' ' || e.modifiers || ' ') NOT LIKE '% native %'
         AND coalesce(t.type_kind, '') NOT IN ('INTERFACE', 'ANNOTATION')
       ORDER BY e.element_id LIMIT 20""";

  private static final String SORTED_VALUES =
      """
      SELECT m.%s FROM code_metric m JOIN code_element e ON e.id = m.element_pk
       WHERE m.analysis_pk = ? AND e.kind = ? AND m.%1$s IS NOT NULL ORDER BY m.%1$s""";

  private static final String TOP_TYPES_SQL =
      """
      SELECT e.element_id, f.relative_path, m.loc, m.unit_count, m.cc_sum, m.cc_max, m.cc_p90
        FROM code_metric m JOIN code_element e ON e.id = m.element_pk
        LEFT JOIN source_file f ON f.id = e.file_pk
       WHERE m.analysis_pk = ? AND e.kind = 'TYPE'
       ORDER BY m.cc_sum DESC, e.element_id LIMIT ?""";

  private static final String LARGEST_METHOD =
      """
      SELECT e.element_id, f.relative_path, m.loc, m.unit_count, m.cc_sum, m.cc_max, m.cc_p90
        FROM code_metric m JOIN code_element e ON e.id = m.element_pk
        LEFT JOIN source_file f ON f.id = e.file_pk
       WHERE m.analysis_pk = ? AND e.kind = 'METHOD' AND m.cc_sum IS NOT NULL
       ORDER BY m.cc_sum DESC, e.element_id LIMIT 1""";

  private static final String DUMP =
      """
      SELECT e.element_id, e.kind, m.loc, m.unit_count, m.cc_sum, m.cc_max, m.cc_p90
        FROM code_metric m JOIN code_element e ON e.id = m.element_pk
       WHERE m.analysis_pk = ? ORDER BY e.element_id""";

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  @Test
  void asMetricasAtravessamOCorpusSemMudarUmFatoNemViolarOContratoEDeterministas()
      throws Exception {
    HermeticSystemReader.install();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final String baseCommit = baseCommit(strataRoot);
    final JsonNode baseline = new ObjectMapper().readTree(strataRoot.resolve(BASELINE).toFile());
    final Path corpus = corpusDirectory();
    final Path report = strataRoot.resolve(REPORT);
    final Path dumps = Files.createDirectories(strataRoot.resolve(DUMPS));
    final List<RepositoryResult> results = new ArrayList<>();

    for (final String name : REPOSITORIES) {
      final ValidatedRepository repository = validated(corpus.resolve(name));
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        final JsonNode expected = baselineOf(baseline, name, selection);
        selections.add(measure(name, repository, selection, expected, dumps, 1));
        final List<RepositoryResult> measured = new ArrayList<>(results);
        measured.add(repositoryResult(name, repository, selections));
        write(report, reportOf(baseCommit, baseline, measured, null));
      }
      results.add(repositoryResult(name, repository, selections));
    }

    final Determinism determinism = determinism(corpus, baseline, dumps);
    write(report, reportOf(baseCommit, baseline, results, determinism));
    assertPreRegistration(results, determinism);
  }

  private SelectionResult measure(
      String name,
      ValidatedRepository repository,
      Selection selection,
      JsonNode expected,
      Path dumps,
      int pass)
      throws Exception {
    final List<SourceRoot> roots = roots(repository, selection);
    final Path data = temp.toRealPath().resolve(name + "-" + selection.name() + "-run" + pass);
    final Path dump =
        DETERMINISM_REPOSITORY.equals(name) && selection == Selection.ALL
            ? dumpOf(dumps, name, selection, pass)
            : null;
    final PersistenceRoot persistence = PersistenceRoot.prepare(data);
    final Run run;
    final ReadBack readBack;
    final Path database;
    System.gc();
    try (RepositoryCache cache = open(persistence, repository)) {
      final SqliteConnections connections = cache.connections();
      database = connections.databaseFile();
      final long analysisPk = insertRunning(connections, repository, selection);
      final ElementWriter writer =
          new ElementWriter(
              connections, analysisPk, roots, repository.snapshot().inventory().javaSources());
      final TimedSink sink = new TimedSink(writer);
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
            new Run(
                analysis,
                extractor.metricTime(),
                sink.nanos() / NANOS_PER_MILLI,
                wallMillis,
                new Heap(
                    meter.peakLiveBytes(),
                    meter.peakUsedBytes(),
                    optionalLong(expected, "heap", "peakLiveBytes"),
                    optionalLong(expected, "heap", "peakUsedBytes")));
      }
      readBack = connections.read(c -> readBack(c, analysisPk, dump));
    }
    final long cacheBytes = Files.size(database);
    TestCaches.deleteTree(data);
    final FileCheck files = fileCheck(repository.snapshot().root(), run.analysis(), readBack);
    return completed(selection, roots, run, readBack, files, cacheBytes, expected);
  }

  private static SelectionResult completed(
      Selection selection,
      List<SourceRoot> roots,
      Run run,
      ReadBack readBack,
      FileCheck files,
      long cacheBytes,
      JsonNode expected) {
    final SourceAnalysis.Diagnostics diagnostics = run.analysis().diagnostics();
    final FileOutcomes outcomes = files(run.analysis());
    final Map<String, Long> violations = new LinkedHashMap<>(readBack.violations());
    violations.put("fileRowMissing", files.rowMissing());
    violations.put("fileRowUnexpected", files.rowUnexpected());
    violations.put("fileWithoutElement", files.withoutElement());
    violations.put("fileLocNotSourceFileLoc", files.notSourceFileLoc());
    violations.put("fileLocNotRecount", files.notRecount());
    final long locMillis = run.metricTime().loc().toMillis();
    final long ccMillis = run.metricTime().cc().toMillis();
    return new SelectionResult(
        selection.name(),
        rootsByKind(roots),
        COMPLETED,
        outcomes,
        readBack.elements(),
        readBack.dependencies(),
        baselineDifferences(readBack.elements(), readBack.dependencies(), outcomes, expected),
        readBack.metricRows(),
        violations,
        readBack.samples(),
        readBack.methodsWithoutCc(),
        readBack.methodsWithoutCcOther(),
        readBack.distributions(),
        readBack.fileLocTotal(),
        new Timing(
            run.wallMillis(),
            diagnostics.preParseMillis(),
            diagnostics.buildMillis(),
            diagnostics.visitMillis(),
            run.sinkMillis(),
            optionalLong(expected, "write", "sinkMillis"),
            locMillis,
            ccMillis,
            files.recountNanos() / NANOS_PER_MILLI,
            locMillis + ccMillis + files.recountNanos() / NANOS_PER_MILLI,
            optionalLong(expected, "timing", "wallMillis")),
        run.heap(),
        cacheBytes,
        optionalLong(expected, "cacheBytes"),
        readBack.dump());
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
        null,
        new Timing(wallMillis, 0, 0, 0, 0, null, 0, 0, 0, 0, null),
        new Heap(meter.peakLiveBytes(), meter.peakUsedBytes(), null, null),
        null,
        null,
        null);
  }

  private Determinism determinism(Path corpus, JsonNode baseline, Path dumps) throws Exception {
    final ValidatedRepository repository = validated(corpus.resolve(DETERMINISM_REPOSITORY));
    final Path first = dumpOf(dumps, DETERMINISM_REPOSITORY, Selection.ALL, 1);
    final Path second = dumpOf(dumps, DETERMINISM_REPOSITORY, Selection.ALL, 2);
    final SelectionResult rerun =
        measure(
            DETERMINISM_REPOSITORY,
            repository,
            Selection.ALL,
            baselineOf(baseline, DETERMINISM_REPOSITORY, Selection.ALL),
            dumps,
            2);
    final byte[] one = Files.readAllBytes(first);
    final byte[] two = Files.readAllBytes(second);
    return new Determinism(
        DETERMINISM_REPOSITORY,
        Selection.ALL.name(),
        DUMPS + "/" + first.getFileName(),
        DUMPS + "/" + second.getFileName(),
        rerun.dump() == null ? 0 : rerun.dump().rows(),
        sha256(one),
        sha256(two),
        Arrays.equals(one, two));
  }

  private static ReadBack readBack(Connection c, long analysisPk, Path dump) throws SQLException {
    final Map<String, Long> violations = new LinkedHashMap<>();
    final Map<String, List<String>> samples = new LinkedHashMap<>();
    for (final Map.Entry<String, String> violation : VIOLATIONS.entrySet()) {
      final List<String> ids = strings(c, violation.getValue(), analysisPk);
      violations.put(violation.getKey(), (long) ids.size());
      if (!ids.isEmpty()) {
        samples.put(violation.getKey(), List.copyOf(ids.subList(0, Math.min(SAMPLES, ids.size()))));
      }
    }
    final int[] typeCc = sorted(c, "cc_sum", "TYPE", analysisPk);
    final int[] typeLoc = sorted(c, "loc", "TYPE", analysisPk);
    final int[] methodCc = sorted(c, "cc_sum", "METHOD", analysisPk);
    final int[] fileLoc = sorted(c, "loc", "FILE", analysisPk);
    final Distributions distributions =
        new Distributions(
            Percentiles.of(typeCc),
            Percentiles.of(typeLoc),
            Percentiles.of(methodCc),
            ranked(c, TOP_TYPES_SQL, analysisPk, TOP_TYPES),
            ranked(c, LARGEST_METHOD, analysisPk, 1).stream().findFirst().orElse(null));
    return new ReadBack(
        elements(c, analysisPk),
        dependencies(c, analysisPk),
        metricRows(c, analysisPk),
        violations,
        samples,
        byKey(c, WITHOUT_CC, analysisPk),
        strings(c, WITHOUT_CC_OTHER, analysisPk),
        distributions,
        Arrays.stream(fileLoc).asLongStream().sum(),
        fileRows(c, analysisPk),
        dumpUnchecked(c, analysisPk, dump));
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
    statuses.putAll(byKey(c, FILE_STATUSES, analysisPk));
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

  private static Map<String, Integer> fileRows(Connection c, long analysisPk) throws SQLException {
    final Map<String, Integer> rows = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(FILE_ROWS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.put(result.getString(1), result.getInt(2) == 1 ? result.getInt(3) : null);
        }
      }
    }
    return rows;
  }

  private static Dump dumpUnchecked(Connection c, long analysisPk, Path target)
      throws SQLException {
    try {
      return dump(c, analysisPk, target);
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  private static Dump dump(Connection c, long analysisPk, Path target)
      throws SQLException, IOException {
    if (target == null) {
      return null;
    }
    long rows = 0;
    try (PreparedStatement statement = c.prepareStatement(DUMP);
        BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          final StringBuilder line = new StringBuilder(result.getString(1));
          for (int column = 2; column <= 7; column++) {
            final String value = result.getString(column);
            line.append('\t').append(value == null ? "NULL" : value);
          }
          out.write(line.append('\n').toString());
          rows++;
        }
      }
    }
    return new Dump(DUMPS + "/" + target.getFileName(), rows, sha256(Files.readAllBytes(target)));
  }

  private static FileCheck fileCheck(Path root, SourceAnalysis analysis, ReadBack readBack)
      throws IOException {
    final Map<String, Integer> rows = readBack.fileRows();
    long rowMissing = 0;
    long rowUnexpected = 0;
    long withoutElement = 0;
    long notSourceFileLoc = 0;
    long notRecount = 0;
    long recountNanos = 0;
    for (final SourceFile file : analysis.files()) {
      if (!rows.containsKey(file.relativePath())) {
        withoutElement++;
        continue;
      }
      final Integer row = rows.get(file.relativePath());
      final boolean decoded =
          file.status() != SourceFileStatus.SKIPPED_TOO_LARGE
              && file.cause() != ParseFailureCause.UNREADABLE;
      if (decoded != (row != null)) {
        if (decoded) {
          rowMissing++;
        } else {
          rowUnexpected++;
        }
        continue;
      }
      if (row == null) {
        continue;
      }
      if (!row.equals(file.loc())) {
        notSourceFileLoc++;
      }
      final char[] text =
          SourceCharsets.decode(Files.readAllBytes(root.resolve(file.relativePath())));
      final long started = System.nanoTime();
      final int loc = LocCounter.of(text).loc();
      recountNanos += System.nanoTime() - started;
      if (loc != row) {
        notRecount++;
      }
    }
    return new FileCheck(
        rowMissing, rowUnexpected, withoutElement, notSourceFileLoc, notRecount, recountNanos);
  }

  private static Map<String, String> violations() {
    final String internal =
        " FROM code_metric m JOIN code_element e ON e.id = m.element_pk"
            + " WHERE m.analysis_pk = ?1 AND e.is_external = 0";
    final Map<String, String> queries = new LinkedHashMap<>();
    for (final String kind : List.of("PACKAGE", "TYPE", "METHOD")) {
      queries.put(
          "missing" + kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT),
          "SELECT e.element_id FROM code_element e LEFT JOIN code_metric m ON m.element_pk = e.id"
              + " WHERE e.analysis_pk = ?1 AND e.is_external = 0 AND e.kind = '"
              + kind
              + "' AND m.element_pk IS NULL");
    }
    queries.put(
        "externalRows",
        "SELECT e.element_id FROM code_metric m JOIN code_element e ON e.id = m.element_pk"
            + " WHERE m.analysis_pk = ?1 AND e.is_external = 1");
    queries.put(
        "foreignAnalysis",
        "SELECT CAST(m.element_pk AS TEXT) FROM code_metric m WHERE m.analysis_pk = ?1 AND NOT"
            + " EXISTS (SELECT 1 FROM code_element e WHERE e.id = m.element_pk"
            + " AND e.analysis_pk = m.analysis_pk)");
    queries.put(
        "nullPattern",
        "SELECT e.kind || ' ' || e.element_id"
            + internal
            + " AND NOT coalesce(CASE e.kind"
            + " WHEN 'FILE' THEN m.unit_count IS NULL AND m.cc_sum IS NULL AND m.cc_max IS NULL"
            + " AND m.cc_p90 IS NULL"
            + " WHEN 'PACKAGE' THEN m.unit_count IS NOT NULL AND m.cc_sum IS NOT NULL"
            + " AND m.cc_max IS NOT NULL AND m.cc_p90 IS NULL"
            + " WHEN 'TYPE' THEN m.unit_count IS NOT NULL AND m.cc_sum IS NOT NULL"
            + " AND m.cc_max IS NOT NULL AND m.cc_p90 IS NOT NULL"
            + " WHEN 'METHOD' THEN m.unit_count IS NOT NULL AND m.cc_p90 IS NULL"
            + " AND (m.cc_sum IS NULL) = (m.unit_count = 0)"
            + " AND (m.cc_max IS NULL) = (m.unit_count = 0)"
            + " ELSE 0 END, 0)");
    queries.put(
        "typeBelowItsMethods",
        "SELECT t.element_id FROM code_element t JOIN code_metric mt ON mt.element_pk = t.id"
            + " LEFT JOIN code_element me ON me.analysis_pk = t.analysis_pk"
            + " AND me.parent_pk = t.id AND me.kind = 'METHOD'"
            + " LEFT JOIN code_metric mm ON mm.element_pk = me.id"
            + " WHERE t.analysis_pk = ?1 AND t.kind = 'TYPE' AND t.is_external = 0"
            + " GROUP BY t.id HAVING mt.cc_sum < coalesce(sum(mm.cc_sum), 0)"
            + " OR mt.unit_count < coalesce(sum(mm.unit_count), 0)");
    queries.put(
        "packageNotItsSum",
        "SELECT p.element_id FROM code_element p JOIN code_metric mp ON mp.element_pk = p.id"
            + " LEFT JOIN (SELECT f.parent_pk AS pk, sum(mf.loc) AS loc FROM code_element f"
            + " JOIN code_metric mf ON mf.element_pk = f.id"
            + " WHERE f.analysis_pk = ?1 AND f.kind = 'FILE' GROUP BY f.parent_pk) fs"
            + " ON fs.pk = p.id"
            + " LEFT JOIN (SELECT t.parent_pk AS pk, sum(mt.unit_count) AS units,"
            + " sum(mt.cc_sum) AS cc, max(mt.cc_max) AS mx FROM code_element t"
            + " JOIN code_metric mt ON mt.element_pk = t.id WHERE t.analysis_pk = ?1"
            + " AND t.kind = 'TYPE' AND t.is_external = 0 GROUP BY t.parent_pk) ts"
            + " ON ts.pk = p.id"
            + " WHERE p.analysis_pk = ?1 AND p.kind = 'PACKAGE'"
            + " AND (mp.loc IS NOT coalesce(fs.loc, 0) OR mp.unit_count IS NOT coalesce(ts.units, 0)"
            + " OR mp.cc_sum IS NOT coalesce(ts.cc, 0) OR mp.cc_max IS NOT coalesce(ts.mx, 0))");
    queries.put(
        "locOutsideLines",
        "SELECT e.kind || ' ' || e.element_id"
            + internal
            + " AND e.kind IN ('TYPE', 'METHOD') AND CASE"
            + " WHEN e.start_line IS NULL OR e.end_line IS NULL THEN m.loc <> 0"
            + " ELSE m.loc > e.end_line - e.start_line + 1 END");
    return queries;
  }

  private static void assertPreRegistration(
      List<RepositoryResult> results, Determinism determinism) {
    final SoftAssertions softly = new SoftAssertions();
    for (final RepositoryResult repository : results) {
      for (final SelectionResult measured : repository.selections()) {
        final String where = repository.name() + " " + measured.selection() + ": ";
        softly.assertThat(measured.outcome()).as(where + "dentro de -Xmx2g").isEqualTo(COMPLETED);
        if (!COMPLETED.equals(measured.outcome())) {
          continue;
        }
        softly
            .assertThat(measured.baselineDifferences())
            .as(where + "os elementos, as dependências e os desfechos da linha de base")
            .isEmpty();
        softly
            .assertThat(measured.violations())
            .as(where + "zero violações de cada tipo")
            .allSatisfy((kind, count) -> assertThat(count).as(kind).isZero());
        softly
            .assertThat(measured.heap().peakLiveBytes())
            .as(where + "o pico vivo, até 64 MiB acima do da linha de base")
            .isLessThanOrEqualTo(
                Optional.ofNullable(measured.heap().baselinePeakLiveBytes()).orElse(0L)
                    + HEAP_TOLERANCE_BYTES);
        if (BUDGET_REPOSITORY.equals(repository.name())
            && Selection.ALL.name().equals(measured.selection())) {
          softly
              .assertThat(measured.timing().metricMillis())
              .as(where + "LOC + CC, o loc do FILE incluído, dentro de 5 s")
              .isLessThanOrEqualTo(METRIC_BUDGET_MILLIS);
        }
      }
    }
    softly
        .assertThat(determinism.identical())
        .as("os dois despejos de code_metric do commons-lang ALL")
        .isTrue();
    softly.assertAll();
  }

  private static List<String> baselineDifferences(
      Elements elements, Dependencies dependencies, FileOutcomes files, JsonNode expected) {
    final List<String> differences = new ArrayList<>();
    final JsonNode baseline = expected.path("elements");
    compare(differences, "elements.packages", elements.packages(), baseline.path("packages"));
    compare(differences, "elements.types", elements.types(), baseline.path("types"));
    compare(differences, "elements.methods", elements.methods(), baseline.path("methods"));
    compare(differences, "elements.files", elements.files(), baseline.path("files"));
    compare(
        differences,
        "elements.externalTypes",
        elements.externalTypes(),
        baseline.path("externalTypes"));
    compare(
        differences, "elements.sourceFiles", elements.sourceFiles(), baseline.path("sourceFiles"));
    compare(
        differences, "elements.sourceRoots", elements.sourceRoots(), baseline.path("sourceRoots"));
    elements
        .sourceFileStatuses()
        .forEach(
            (status, count) ->
                compare(
                    differences,
                    "elements.sourceFileStatuses." + status,
                    count,
                    baseline.path("sourceFileStatuses").path(status)));
    compare(
        differences,
        "dependencies.rows",
        dependencies.rows(),
        expected.path("dependencies").path("rows"));
    compare(
        differences,
        "dependencies.occurrences",
        dependencies.occurrences(),
        expected.path("dependencies").path("occurrences"));
    compare(
        differences,
        "evidence.rows",
        dependencies.evidenceRows(),
        expected.path("evidence").path("rows"));
    compare(
        differences,
        "modules.rows",
        dependencies.moduleRows(),
        expected.path("modules").path("rows"));
    final JsonNode outcomes = expected.path("files");
    compare(differences, "files.total", files.total(), outcomes.path("total"));
    compare(differences, "files.analyzed", files.analyzed(), outcomes.path("analyzed"));
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

  private static List<String> strings(Connection c, String sql, long analysisPk)
      throws SQLException {
    final List<String> values = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          values.add(result.getString(1));
        }
      }
    }
    return values;
  }

  private static Map<String, Long> byKey(Connection c, String sql, long analysisPk)
      throws SQLException {
    final Map<String, Long> counts = new LinkedHashMap<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          counts.put(result.getString(1), result.getLong(2));
        }
      }
    }
    return counts;
  }

  private static Integer nullableInt(ResultSet result, int column) throws SQLException {
    final int value = result.getInt(column);
    return result.wasNull() ? null : value;
  }

  private static int[] sorted(Connection c, String column, String kind, long analysisPk)
      throws SQLException {
    final List<Integer> values = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(SORTED_VALUES.formatted(column))) {
      statement.setLong(1, analysisPk);
      statement.setString(2, kind);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          values.add(result.getInt(1));
        }
      }
    }
    return values.stream().mapToInt(Integer::intValue).toArray();
  }

  private static List<Ranked> ranked(Connection c, String sql, long analysisPk, int limit)
      throws SQLException {
    final List<Ranked> rows = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      if (limit > 1) {
        statement.setInt(2, limit);
      }
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(
              new Ranked(
                  result.getString(1),
                  result.getString(2),
                  result.getInt(3),
                  result.getInt(4),
                  result.getInt(5),
                  result.getInt(6),
                  nullableInt(result, 7)));
        }
      }
    }
    return List.copyOf(rows);
  }

  private static FileOutcomes files(SourceAnalysis analysis) {
    int analyzed = 0;
    int tooLarge = 0;
    final Map<String, Integer> causes = new LinkedHashMap<>();
    for (final ParseFailureCause cause : ParseFailureCause.values()) {
      causes.put(cause.name(), 0);
    }
    for (final SourceFile file : analysis.files()) {
      switch (file.status()) {
        case ANALYZED -> analyzed++;
        case SKIPPED_TOO_LARGE -> tooLarge++;
        case PARSE_FAILED -> causes.merge(file.cause().name(), 1, Integer::sum);
      }
    }
    return new FileOutcomes(
        analysis.totalFiles(), analyzed, tooLarge, analysis.parseFailures(), causes);
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

  private static Report reportOf(
      String baseCommit,
      JsonNode baseline,
      List<RepositoryResult> repositories,
      Determinism determinism) {
    final Map<String, String> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.description);
    }
    return new Report(
        SCHEMA,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        baseCommit,
        MEASURE,
        new Settings(
            System.getProperty("strata.spoon.version", "unknown"),
            AnalysisConstants.ELEMENT_FLUSH_ROWS,
            AnalysisConstants.MAX_MODEL_SOURCE_BYTES,
            String.join(", ", VISIT_ORDER.stream().map(SourceRootKind::name).toList()),
            selections,
            new Baseline(
                BASELINE,
                baseline.path("measuredAt").asText(),
                baseline.path("baseCommit").asText()),
            METRIC_BUDGET_MILLIS,
            HEAP_TOLERANCE_BYTES),
        Environment.ofThisJvm(),
        determinism,
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

  private static Path dumpOf(Path dumps, String name, Selection selection, int run) {
    return dumps.resolve(name + "-" + selection.name() + "-run" + run + ".tsv");
  }

  private static Long optionalLong(JsonNode node, String... path) {
    JsonNode current = node;
    for (final String field : path) {
      current = current.path(field);
    }
    return current.isNumber() ? current.asLong() : null;
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

  private static void write(Path report, Report content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
  }

  private static final class TimedSink implements StructureSink {

    private final StructureSink delegate;
    private long nanos;

    TimedSink(StructureSink delegate) {
      this.delegate = delegate;
    }

    long nanos() {
      return nanos;
    }

    @Override
    public void begin(List<SourceFile> files) {
      final long started = System.nanoTime();
      delegate.begin(files);
      nanos += System.nanoTime() - started;
    }

    @Override
    public void accept(BatchElements elements) {
      final long started = System.nanoTime();
      delegate.accept(elements);
      nanos += System.nanoTime() - started;
    }

    @Override
    public void accept(BatchStructure batch) {
      final long started = System.nanoTime();
      delegate.accept(batch);
      nanos += System.nanoTime() - started;
    }

    @Override
    public void end(List<SourceFile> files, List<FileElement> fileElements) {
      final long started = System.nanoTime();
      delegate.end(files, fileElements);
      nanos += System.nanoTime() - started;
    }
  }

  private record Run(
      SourceAnalysis analysis,
      StructureExtractor.MetricTime metricTime,
      long sinkMillis,
      long wallMillis,
      Heap heap) {}

  private record ReadBack(
      Elements elements,
      Dependencies dependencies,
      Map<String, Long> metricRows,
      Map<String, Long> violations,
      Map<String, List<String>> samples,
      Map<String, Long> methodsWithoutCc,
      List<String> methodsWithoutCcOther,
      Distributions distributions,
      long fileLocTotal,
      Map<String, Integer> fileRows,
      Dump dump) {}

  private record FileCheck(
      long rowMissing,
      long rowUnexpected,
      long withoutElement,
      long notSourceFileLoc,
      long notRecount,
      long recountNanos) {}

  record Report(
      int schema,
      String measuredAt,
      String baseCommit,
      String measure,
      Settings settings,
      Environment environment,
      Determinism determinism,
      List<RepositoryResult> repositories) {}

  record Settings(
      String spoonVersion,
      int elementFlushRows,
      long maxModelSourceBytes,
      String rootOrder,
      Map<String, String> selections,
      Baseline baseline,
      long metricBudgetMillis,
      long heapToleranceBytes) {}

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
      List<String> baselineDifferences,
      Map<String, Long> metricRows,
      Map<String, Long> violations,
      Map<String, List<String>> samples,
      Map<String, Long> methodsWithoutCc,
      List<String> methodsWithoutCcOther,
      Distributions distributions,
      Long fileLocTotal,
      Timing timing,
      Heap heap,
      Long cacheBytes,
      Long baselineCacheBytes,
      Dump dump) {}

  record Dependencies(long rows, long occurrences, long evidenceRows, long moduleRows) {}

  record Timing(
      long wallMillis,
      long preParseMillis,
      long buildMillis,
      long visitMillis,
      long sinkMillis,
      Long baselineSinkMillis,
      long locMillis,
      long ccMillis,
      long fileLocMillis,
      long metricMillis,
      Long baselineWallMillis) {}

  record Distributions(
      Percentiles typeCcSum,
      Percentiles typeLoc,
      Percentiles methodCcSum,
      List<Ranked> largestTypesByCcSum,
      Ranked largestMethodByCcSum) {}

  record Percentiles(int count, int p50, int p90, int p99, int max) {

    static Percentiles of(int[] ascending) {
      final int n = ascending.length;
      return n == 0
          ? new Percentiles(0, 0, 0, 0, 0)
          : new Percentiles(
              n, rank(ascending, 50), rank(ascending, 90), rank(ascending, 99), ascending[n - 1]);
    }

    private static int rank(int[] ascending, int percent) {
      return ascending[(int) Math.ceilDiv((long) percent * ascending.length, 100) - 1];
    }
  }

  record Ranked(
      String elementId, String file, int loc, int unitCount, int ccSum, int ccMax, Integer ccP90) {}

  record Dump(String file, long rows, String sha256) {}

  record Determinism(
      String repository,
      String selection,
      String firstDump,
      String secondDump,
      long rows,
      String firstSha256,
      String secondSha256,
      boolean identical) {}
}
