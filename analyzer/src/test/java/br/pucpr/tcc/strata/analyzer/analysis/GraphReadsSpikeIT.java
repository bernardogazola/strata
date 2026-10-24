package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Dependencies;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Elements;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Environment;
import br.pucpr.tcc.strata.analyzer.analysis.StagesBaselineReport.Host;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class GraphReadsSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;
  private static final String REPORT = "benchmarks/graph-reads.json";
  private static final String STAGES_BASELINE = "benchmarks/stages-baseline.json";
  private static final String GRAPH_BASELINE = "benchmarks/pagerank-determinism.json";
  private static final String WAL_BASELINE = "benchmarks/dependency-extraction.json";
  private static final String WORK = "analyzer/target/graph-reads";
  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final String ANALYSIS_COMPLETED = "analysis.completed";
  private static final String STAGE_COMPLETED = "analysis.stage.completed";
  private static final String GRAPH_STAGE = AnalysisStage.STRUCTURAL_GRAPH.name();
  private static final int RUNS = 2;
  private static final int WARM_RUNS = 5;
  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HexFormat HEX = HexFormat.of();

  private static final long LATENCY_BUDGET_MICROS = 100_000L;

  private static final long WAL_CEILING_BYTES = 67_108_864L;

  private static final long SOFT_RESPONSE_BYTES = 8_388_608L;

  private static final double STAGE_DELTA_BUDGET_MILLIS = 100.0;

  private static final int CURSOR_CEILING_CHARS = 256;

  private static final int OUTLINE_PAGE_LIMIT = 500;

  private static final List<String> SEARCH_KINDS = List.of("PACKAGE", "TYPE");

  private static final List<String> QUERIES =
      List.of("service", "a", "Abstract", "test", "order", "acao", "zzqx");

  private static final long WAL_SAMPLE_NANOS = 1_000_000L;

  private static final byte[] ID_PREFIX = "{\"id\":\"".getBytes(StandardCharsets.UTF_8);

  private static final int REQUEST_ID_CHARS = 30;

  private static final int AFTER_ID = ID_PREFIX.length + REQUEST_ID_CHARS + 1;

  private static final int MAX_PAGES = 1_000;

  private static final double SHARE_SCALE = 10_000.0;

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", "broken-tree", "junit5", "spring-framework", "elasticsearch");

  private static final String LARGEST = "elasticsearch";

  private static final String MEASURE =
      "The structural reads over the corpus. Per repository and selection, two analyses"
          + " through the real AnalysisRegistry (RegistrySettings.production, Stages.production(),"
          + " which wires STRUCTURAL_GRAPH) on fresh data directories; a daemon thread"
          + " samples the size of analysis.sqlite-wal every millisecond during each analysis. After"
          + " the terminal event, with the registry still open, every request goes through a"
          + " Dispatcher built on Handlers.all and the production JsonLines.Writer into a buffer"
          + " (Lifecycle's path for one frame, without stdin and the pipe), with the interface's"
          + " parameters. Analysis 1: the read requests, each timed once (the first call, also the"
          + " warm-up) and five more times, in the product's order; then the SWEEP, recorded as a"
          + " transcript: level 1, every outline root page, per package the TYPE slice and every"
          + " children page, and the seven searches; then the IT's own SQL over the persisted rows;"
          + " then the closed cache. Analysis 2: the same sweep with the same request ids, compared"
          + " byte for byte.";

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

    Map<String, Object> payload() {
      final Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("includeTests", includeTests);
      payload.put("historyScope", HistoryScope.ALL.name());
      payload.put("historyScopeCommits", null);
      payload.put("sourceRootIds", null);
      return payload;
    }
  }

  private record Rollup(
      int groups, long groupEdges, int largestGroup, int singletonGroups, double weightShareKept) {}

  private static final Map<String, Rollup> ROLLUPS =
      Map.of(
          key("spring-framework", Selection.DEFAULT),
          new Rollup(300, 2_411, 8, 200, 0.9127),
          key("spring-framework", Selection.ALL),
          new Rollup(300, 3_302, 13, 168, 0.8783),
          key("elasticsearch", Selection.DEFAULT),
          new Rollup(300, 4_553, 103, 127, 0.8895),
          key("elasticsearch", Selection.ALL),
          new Rollup(300, 5_589, 152, 132, 0.8595));

  private record TypeCensus(
      String largestId, long largestTypes, long maxIntraPairs, long typesSum, long intraPairsSum) {}

  private static final String LANG3 = "pkg:org.apache.commons.lang3";
  private static final String MAPPER = "pkg:org.elasticsearch.index.mapper";

  private static final Map<String, TypeCensus> TYPE_CENSUS =
      Map.of(
          key("commons-lang", Selection.DEFAULT),
          new TypeCensus(LANG3, 73, 113, 364, 464),
          key("commons-lang", Selection.ALL),
          new TypeCensus(LANG3, 262, 450, 958, 1_511),
          key("broken-tree", Selection.DEFAULT),
          new TypeCensus(LANG3, 72, 110, 362, 458),
          key("broken-tree", Selection.ALL),
          new TypeCensus(LANG3, 261, 446, 956, 1_503),
          key("junit5", Selection.DEFAULT),
          new TypeCensus("pkg:org.junit.jupiter.api", 88, 155, 1_110, 1_628),
          key("junit5", Selection.ALL),
          new TypeCensus("pkg:org.junit.jupiter.engine.extension", 582, 417, 3_751, 3_942),
          key("spring-framework", Selection.DEFAULT),
          new TypeCensus("pkg:org.springframework.util", 141, 328, 6_923, 10_364),
          key("spring-framework", Selection.ALL),
          new TypeCensus("pkg:org.springframework.core.annotation", 829, 1_412, 18_468, 23_433),
          key("elasticsearch", Selection.DEFAULT),
          new TypeCensus(MAPPER, 556, 2_685, 26_883, 45_666),
          key("elasticsearch", Selection.ALL),
          new TypeCensus(MAPPER, 779, 3_785, 39_364, 72_234));

  private static final Map<String, Long> REFERENCE_CACHE_BYTES =
      Map.of(
          key("commons-lang", Selection.DEFAULT), 5_124_096L,
          key("commons-lang", Selection.ALL), 12_312_576L,
          key("broken-tree", Selection.DEFAULT), 5_058_560L,
          key("broken-tree", Selection.ALL), 12_251_136L,
          key("junit5", Selection.DEFAULT), 12_627_968L,
          key("junit5", Selection.ALL), 32_120_832L,
          key("spring-framework", Selection.DEFAULT), 91_484_160L,
          key("spring-framework", Selection.ALL), 190_435_328L,
          key("elasticsearch", Selection.DEFAULT), 416_649_216L,
          key("elasticsearch", Selection.ALL), 690_364_416L);

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

  private static final String STAGE_ROWS =
      "SELECT stage, status, duration_ms, counters_json FROM analysis_stage WHERE analysis_pk = ?";

  private static final String PACKAGES =
      """
      SELECT e.element_id, e.qualified_name,
             (SELECT count(*) FROM code_element t
               WHERE t.analysis_pk = e.analysis_pk AND t.parent_pk = e.id
                 AND t.kind = 'TYPE' AND t.is_external = 0)
        FROM graph_metric g JOIN code_element e ON e.id = g.element_pk
       WHERE g.analysis_pk = ? AND g.granularity = 'PACKAGE'""";

  private static final String TYPES =
      """
      SELECT p.element_id, t.element_id FROM code_element t
        JOIN code_element p ON p.id = t.parent_pk
       WHERE t.analysis_pk = ? AND t.kind = 'TYPE' AND t.is_external = 0""";

  private static final String PACKAGE_EDGES =
      """
      SELECT s.element_id, t.element_id, p.type_pair_count FROM package_edge p
        JOIN code_element s ON s.id = p.source_pk
        JOIN code_element t ON t.id = p.target_pk
       WHERE p.analysis_pk = ?""";

  private static final String INTRA_PAIRS =
      """
      SELECT p.element_id, count(*) FROM
             (SELECT DISTINCT d.source_pk, d.target_pk FROM dependency d
               WHERE d.analysis_pk = ? AND d.source_pk <> d.target_pk) x
        JOIN code_element s ON s.id = x.source_pk
        JOIN code_element t ON t.id = x.target_pk
        JOIN code_element p ON p.id = s.parent_pk
       WHERE s.kind = 'TYPE' AND s.is_external = 0 AND t.kind = 'TYPE' AND t.is_external = 0
         AND s.parent_pk = t.parent_pk
       GROUP BY p.element_id""";

  @Test
  void asTresLeiturasDoGrafoPeloDespachanteCabemNoOrcamentoEMantemOPreRegistro() throws Exception {
    HermeticSystemReader.install();
    final double loadAtStart = loadAverage();
    final Instant startedAt = Instant.now();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final Environment environment = environment();
    final String baseCommit = headOf(strataRoot).substring(0, 7);
    final Baselines baselines =
        new Baselines(
            JSON.readTree(strataRoot.resolve(STAGES_BASELINE).toFile()),
            JSON.readTree(strataRoot.resolve(GRAPH_BASELINE).toFile()),
            JSON.readTree(strataRoot.resolve(WAL_BASELINE).toFile()));
    final Path corpus = corpusDirectory();
    final Path work = strataRoot.resolve(WORK);
    final List<String> measured = repositoriesToMeasure();
    final Path report =
        measured.equals(REPOSITORIES)
            ? strataRoot.resolve(REPORT)
            : work.resolveSibling("graph-reads.restricted.json");
    deleteTree(work);
    Files.createDirectories(work);

    final List<RepositoryResult> repositories = new ArrayList<>();
    for (final String name : measured) {
      final Path repository = corpus.resolve(name).toRealPath();
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        selections.add(measureSelection(name, repository, selection, work, strataRoot, baselines));
        final List<RepositoryResult> partial = new ArrayList<>(repositories);
        partial.add(new RepositoryResult(name, headOf(repository), List.copyOf(selections)));
        write(
            report,
            new Report(
                SCHEMA,
                startedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
                baseCommit,
                MEASURE,
                settings(loadAtStart, null),
                environment,
                List.of(),
                partial));
      }
      repositories.add(new RepositoryResult(name, headOf(repository), List.copyOf(selections)));
    }

    final List<Expectation> expectations = expectations(repositories, baselines);
    write(
        report,
        new Report(
            SCHEMA,
            startedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            baseCommit,
            MEASURE,
            settings(loadAtStart, loadAverage()),
            environment,
            expectations,
            repositories));

    final SoftAssertions softly = new SoftAssertions();
    for (final Expectation expectation : expectations) {
      softly
          .assertThat(expectation.met())
          .as(
              "%s (%s): esperado %s, medido %s",
              expectation.id(), expectation.claim(), expectation.expected(), expectation.measured())
          .isTrue();
    }
    softly.assertAll();
  }

  private SelectionResult measureSelection(
      String name,
      Path repository,
      Selection selection,
      Path work,
      Path strataRoot,
      Baselines baselines)
      throws Exception {
    final String label = name + "-" + selection.name();
    final List<RunResult> runs = new ArrayList<>();
    Reads reads = null;
    Oracle oracle = null;
    for (int run = 1; run <= RUNS; run++) {
      final boolean first = run == 1;
      final Analyzed analyzed = analyze(label, repository, selection, run, work, strataRoot, first);
      runs.add(analyzed.run());
      if (first) {
        reads = analyzed.reads();
        oracle = analyzed.oracle();
      }
    }
    Objects.requireNonNull(reads, "reads");
    Objects.requireNonNull(oracle, "oracle");
    return new SelectionResult(
        selection.name(),
        selection.payload(),
        runs,
        oracle.readBack(),
        level1(reads.sweep().level1(), reads.sweep().level1Bytes(), oracle),
        typeLevel(reads.sweep(), oracle),
        outline(reads.sweep(), oracle),
        reads.sweep().searches(),
        reads.latency(),
        reads.readPhase(),
        determinism(runs, reads.latency()),
        deltas(name, selection, runs, baselines));
  }

  private Analyzed analyze(
      String label,
      Path repository,
      Selection selection,
      int run,
      Path work,
      Path strataRoot,
      boolean measureReads)
      throws Exception {
    final String runLabel = label + "-run" + run;
    final Path data = work.resolve(runLabel);
    final Path transcriptFile = work.resolve(runLabel + "-sweep.jsonl");
    final RecordingFrameSink sink = new RecordingFrameSink();
    final RegistrySettings settings =
        RegistrySettings.production(PersistenceRoot.prepare(data), sink, ANALYZER_VERSION);
    System.gc();
    final double loadStart = loadAverage();
    final AnalysisRegistry registry = new AnalysisRegistry(settings);
    final AnalysisId id;
    final WalMeasure wal;
    final Path database;
    final Reads reads;
    final Oracle oracle;
    try {
      try (WalSampler sampler = WalSampler.start(data.resolve("repositories"))) {
        id = RunFixtures.launch(registry, repository, selection.configuration());
        sink.awaitTerminal(id, RUN_TIMEOUT);
        RunFixtures.runThread(sink, id).join(RUN_TIMEOUT.toMillis());
        wal = sampler.stop();
      }
      final ReadableAnalysis readable =
          registry
              .readable(id)
              .orElseThrow(() -> new AssertionError("a análise " + id + " não ficou legível"));
      database = readable.connections().databaseFile();
      final double readLoadStart = loadAverage();
      System.gc();
      final GcTotals gcBefore = GcTotals.now();
      final List<Latency> latency =
          measureReads ? latency(new Wire(registry, null), id.value()) : List.of();
      final GcTotals gcAfterTimed = GcTotals.now();
      final Sweep sweep;
      try (OutputStream transcript =
          new BufferedOutputStream(Files.newOutputStream(transcriptFile), 1 << 20)) {
        sweep = sweep(new Wire(registry, transcript), id.value());
      }
      final double readLoadEnd = loadAverage();
      final long pk = readable.analysisPk();
      oracle =
          readable
              .connections()
              .read(c -> measureReads ? oracle(c, pk) : new Oracle(readBack(c, pk), null));
      reads =
          new Reads(
              latency,
              sweep,
              new ReadPhase(
                  gcAfterTimed.collections() - gcBefore.collections(),
                  gcAfterTimed.millis() - gcBefore.millis(),
                  new Load(readLoadStart, readLoadEnd)));
    } finally {
      registry.close();
    }
    final long cacheBytes = Files.size(database);
    final double loadEnd = loadAverage();
    final List<Frame> frames = sink.of(id);
    final Transcript transcript =
        new Transcript(
            strataRoot.relativize(transcriptFile).toString(),
            reads.sweep().frames(),
            Files.size(transcriptFile),
            sha256(transcriptFile));
    if (!measureReads) {
      deleteTree(data);
    }
    return new Analyzed(
        RunResult.of(
            run,
            id,
            frames,
            wal,
            cacheBytes,
            new Load(loadStart, loadEnd),
            transcript,
            oracle.readBack(),
            measureReads ? strataRoot.relativize(database).toString() : null),
        measureReads ? reads : null,
        measureReads ? oracle : null);
  }

  private static final class Wire {

    private final Dispatcher dispatcher;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
    private final JsonLines.Writer writer;
    private final OutputStream transcript;
    private long requests;

    Wire(AnalysisRegistry registry, OutputStream transcript) {
      this.dispatcher =
          Dispatcher.of(
              JsonLines.strictInboundMapper(),
              Handlers.all(() -> 0L, new AtomicBoolean(false), registry));
      this.writer =
          new JsonLines.Writer(buffer, new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES);
      this.transcript = transcript;
    }

    Call call(Request request) throws IOException {
      requests++;
      final String id = String.format("req_%026d", requests);
      final ObjectNode frame = JSON.createObjectNode();
      frame.put("protocol", AnalysisConstants.PROTOCOL_VERSION);
      frame.put("kind", "request");
      frame.put("id", id);
      frame.put("method", request.method());
      frame.set("payload", request.payload());
      final byte[] bytes = JSON.writeValueAsBytes(frame);
      buffer.reset();
      final long started = System.nanoTime();
      final Dispatcher.Outcome outcome = dispatcher.dispatch(dispatcher.parse(bytes));
      switch (outcome) {
        case Dispatcher.Outcome.Respond respond -> emit(respond.envelope());
        case Dispatcher.Outcome.Discard discard ->
            throw new AssertionError("o despachante descartou " + request + ": " + discard);
      }
      final long nanos = System.nanoTime() - started;
      final byte[] line = buffer.toByteArray();
      if (transcript != null) {
        transcript.write(line);
      }
      return new Call(nanos, Arrays.copyOf(line, line.length - 1));
    }

    long requests() {
      return requests;
    }

    private void emit(Envelope envelope) throws IOException {
      try {
        writer.emit(envelope);
      } catch (JsonLines.FrameTooLargeException tooLarge) {
        if (!(envelope instanceof Envelope.OkResponse response)) {
          throw tooLarge;
        }
        writer.emit(
            Envelope.error(
                response.id(),
                ErrorCodes.messageTooLarge(tooLarge.limitBytes(), tooLarge.receivedBytes())));
      } catch (IllegalArgumentException unserializable) {
        if (!(envelope instanceof Envelope.OkResponse response)) {
          throw unserializable;
        }
        writer.emit(
            Envelope.error(
                response.id(), ErrorCodes.internalUnexpected(unserializable.getClass().getName())));
      }
    }
  }

  private record Request(String name, String method, ObjectNode payload) {}

  private record Call(long nanos, byte[] frame) {

    JsonNode response() throws IOException {
      final JsonNode response = JSON.readTree(frame);
      if (!response.path("ok").asBoolean(false)) {
        throw new AssertionError("resposta de erro: " + new String(frame, StandardCharsets.UTF_8));
      }
      return response.path("payload");
    }

    boolean samePayload(Call other) {
      return frame.length == other.frame.length
          && Arrays.equals(frame, 0, ID_PREFIX.length, ID_PREFIX, 0, ID_PREFIX.length)
          && Arrays.equals(frame, AFTER_ID, frame.length, other.frame, AFTER_ID, frame.length);
    }
  }

  private static Request slice(String analysisId, String level, String parentId) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    payload.put("level", level);
    if (parentId != null) {
      payload.put("parentId", parentId);
    }
    final String name =
        parentId == null ? "graph.slice " + level : "graph.slice " + level + " " + parentId;
    return new Request(name, "graph.slice", payload);
  }

  private static Request outline(String analysisId, String parentId, String cursor, String name) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    if (cursor != null) {
      payload.put("cursor", cursor);
    }
    payload.put("limit", OUTLINE_PAGE_LIMIT);
    if (parentId == null) {
      payload.putNull("parentId");
    } else {
      payload.put("parentId", parentId);
    }
    return new Request(name, "outline.children", payload);
  }

  private static Request search(String analysisId, String query) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    final ArrayNode kinds = payload.putArray("kinds");
    SEARCH_KINDS.forEach(kinds::add);
    payload.put("query", query);
    return new Request("graph.search " + query, "graph.search", payload);
  }

  private static List<Latency> latency(Wire wire, String analysisId) throws IOException {
    final List<Latency> latency = new ArrayList<>();
    latency.add(timed(wire, slice(analysisId, "PACKAGE", null), 1));
    final Paged root = timedPages(wire, analysisId, null, "outline.children root", latency);
    String largest = null;
    long largestTypes = -1;
    for (final JsonNode item : root.items()) {
      final long types = item.path("childCount").asLong();
      final String itemId = item.path("id").asText();
      if (types > largestTypes
          || (types == largestTypes && compareCodePoints(itemId, largest) < 0)) {
        largest = itemId;
        largestTypes = types;
      }
    }
    Objects.requireNonNull(largest, "a raiz da estrutura não listou pacote nenhum");
    latency.add(timed(wire, slice(analysisId, "TYPE", largest), 1));
    timedPages(wire, analysisId, largest, "outline.children " + largest, latency);
    for (final String query : QUERIES) {
      latency.add(timed(wire, search(analysisId, query), 1));
    }
    return List.copyOf(latency);
  }

  private static Paged timedPages(
      Wire wire, String analysisId, String parentId, String name, List<Latency> latency)
      throws IOException {
    final Latency first = timed(wire, outline(analysisId, parentId, null, name + " first"), 1);
    latency.add(first);
    final List<JsonNode> items = new ArrayList<>();
    first.payload().path("items").forEach(items::add);
    String cursor = textOrNull(first.payload().path("nextCursor"));
    String lastCursor = null;
    int pages = 1;
    while (cursor != null) {
      pages++;
      if (pages > MAX_PAGES) {
        throw new AssertionError(name + ": mais de " + MAX_PAGES + " páginas");
      }
      final JsonNode page =
          wire.call(outline(analysisId, parentId, cursor, name + " page " + pages)).response();
      page.path("items").forEach(items::add);
      lastCursor = cursor;
      cursor = textOrNull(page.path("nextCursor"));
    }
    if (lastCursor != null) {
      latency.add(timed(wire, outline(analysisId, parentId, lastCursor, name + " last"), pages));
    }
    return new Paged(items, pages);
  }

  private static Latency timed(Wire wire, Request request, int page) throws IOException {
    final Call first = wire.call(request);
    final long[] warm = new long[WARM_RUNS];
    boolean identical = true;
    for (int run = 0; run < WARM_RUNS; run++) {
      final Call call = wire.call(request);
      warm[run] = call.nanos() / NANOS_PER_MICRO;
      identical &= call.samePayload(first);
    }
    final long[] sorted = warm.clone();
    Arrays.sort(sorted);
    return new Latency(
        request.name(),
        request.method(),
        page,
        first.nanos() / NANOS_PER_MICRO,
        sorted[WARM_RUNS / 2],
        sorted[WARM_RUNS - 1],
        Arrays.stream(warm).boxed().toList(),
        first.frame().length,
        identical,
        first.response());
  }

  private record Paged(List<JsonNode> items, int pages) {}

  private static Sweep sweep(Wire wire, String analysisId) throws IOException {
    final Call level1 = wire.call(slice(analysisId, "PACKAGE", null));
    final Walked root = walk(wire, analysisId, null);
    final Map<String, TypeSlice> typeSlices = new LinkedHashMap<>();
    final Map<String, Walked> children = new LinkedHashMap<>();
    for (final JsonNode item : root.items()) {
      final String packageId = item.path("id").asText();
      final Call call = wire.call(slice(analysisId, "TYPE", packageId));
      typeSlices.put(packageId, TypeSlice.of(call.response(), call.frame().length));
      children.put(packageId, walk(wire, analysisId, packageId));
    }
    final List<SearchResult> searches = new ArrayList<>();
    for (final String query : QUERIES) {
      final Call call = wire.call(search(analysisId, query));
      final JsonNode results = call.response().path("results");
      searches.add(
          new SearchResult(
              query,
              results.size(),
              call.frame().length,
              results.isEmpty() ? null : results.get(0).path("elementId").asText()));
    }
    return new Sweep(
        level1.response(),
        level1.frame().length,
        root,
        typeSlices,
        children,
        List.copyOf(searches),
        wire.requests());
  }

  private static Walked walk(Wire wire, String analysisId, String parentId) throws IOException {
    final List<JsonNode> items = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    int longestCursor = 0;
    do {
      pages++;
      if (pages > MAX_PAGES) {
        throw new AssertionError(parentId + ": mais de " + MAX_PAGES + " páginas");
      }
      final JsonNode page =
          wire.call(outline(analysisId, parentId, cursor, "outline.children")).response();
      page.path("items").forEach(items::add);
      cursor = textOrNull(page.path("nextCursor"));
      longestCursor = Math.max(longestCursor, cursor == null ? 0 : cursor.length());
    } while (cursor != null);
    return new Walked(List.copyOf(items), pages, longestCursor);
  }

  private record Walked(List<JsonNode> items, int pages, int longestCursor) {}

  private record TypeSlice(
      boolean aggregated,
      boolean truncated,
      long totalsNodes,
      long totalsEdges,
      int nodes,
      int edges,
      long frameBytes) {

    static TypeSlice of(JsonNode slice, long frameBytes) {
      return new TypeSlice(
          slice.path("aggregated").asBoolean(),
          slice.path("truncated").asBoolean(),
          slice.path("totals").path("nodes").asLong(),
          slice.path("totals").path("edges").asLong(),
          slice.path("nodes").size(),
          slice.path("edges").size(),
          frameBytes);
    }
  }

  private record Sweep(
      JsonNode level1,
      long level1Bytes,
      Walked root,
      Map<String, TypeSlice> typeSlices,
      Map<String, Walked> children,
      List<SearchResult> searches,
      long frames) {}

  private static Oracle oracle(Connection c, long pk) throws SQLException {
    final ReadBack readBack = readBack(c, pk);
    final Map<String, OraclePackage> packages = new TreeMap<>(GraphReadsSpikeIT::compareCodePoints);
    try (PreparedStatement statement = c.prepareStatement(PACKAGES)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          packages.put(
              rows.getString(1),
              new OraclePackage(rows.getString(1), rows.getString(2), rows.getLong(3)));
        }
      }
    }
    final Map<String, Set<String>> types = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(TYPES)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          types
              .computeIfAbsent(rows.getString(1), ignored -> new HashSet<>())
              .add(rows.getString(2));
        }
      }
    }
    final List<OracleEdge> edges = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(PACKAGE_EDGES)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          edges.add(new OracleEdge(rows.getString(1), rows.getString(2), rows.getLong(3)));
        }
      }
    }
    final Map<String, Long> intraPairs = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(INTRA_PAIRS)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          intraPairs.put(rows.getString(1), rows.getLong(2));
        }
      }
    }
    return new Oracle(readBack, new Rows(packages, types, edges, intraPairs));
  }

  private static ReadBack readBack(Connection c, long pk) throws SQLException {
    final Map<String, StageRow> stages = new TreeMap<>();
    try (PreparedStatement statement = c.prepareStatement(STAGE_ROWS)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          stages.put(
              rows.getString(1),
              new StageRow(rows.getString(2), rows.getLong(3), JSON.readTree(rows.getString(4))));
        }
      }
    } catch (IOException malformed) {
      throw new AssertionError("counters_json não é JSON", malformed);
    }
    return new ReadBack(elements(c, pk), dependencies(c, pk), metricRows(c, pk), stages);
  }

  private static Elements elements(Connection c, long pk) throws SQLException {
    final Map<String, Long> internal = new HashMap<>();
    long externalTypes = 0;
    try (PreparedStatement statement = c.prepareStatement(ELEMENTS_BY_KIND)) {
      statement.setLong(1, pk);
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
      statement.setLong(1, pk);
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
        longs(c, SOURCE_ROOTS, pk, 1)[0],
        statuses);
  }

  private static Dependencies dependencies(Connection c, long pk) throws SQLException {
    final long[] values = longs(c, DEPENDENCIES, pk, 4);
    return new Dependencies(values[0], values[1], values[2], values[3]);
  }

  private static Map<String, Long> metricRows(Connection c, long pk) throws SQLException {
    final Map<String, Long> rows = new LinkedHashMap<>();
    for (final String kind : List.of("PACKAGE", "TYPE", "METHOD", "FILE", "external")) {
      rows.put(kind, 0L);
    }
    try (PreparedStatement statement = c.prepareStatement(METRIC_ROWS)) {
      statement.setLong(1, pk);
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

  private static long[] longs(Connection c, String sql, long pk, int columns) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, pk);
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

  private record OraclePackage(String id, String qualifiedName, long types) {}

  private record OracleEdge(String source, String target, long weight) {}

  private record Rows(
      Map<String, OraclePackage> packages,
      Map<String, Set<String>> types,
      List<OracleEdge> edges,
      Map<String, Long> intraPairs) {}

  private record Oracle(ReadBack readBack, Rows rows) {}

  private static Level1 level1(JsonNode slice, long frameBytes, Oracle oracle) {
    final int budgetEdges = slice.path("budget").path("edges").asInt();
    final Rows rows = oracle.rows();
    final Set<String> exact = new HashSet<>();
    final List<String> prefixes = new ArrayList<>();
    for (final JsonNode node : slice.path("nodes")) {
      final String id = node.path("id").asText();
      if (id.startsWith("pfx:")) {
        prefixes.add(id.substring("pfx:".length()));
      } else {
        exact.add(id);
      }
    }
    final Map<String, String> groupOf = new HashMap<>();
    final Map<String, Long> members = new HashMap<>();
    long violations = 0;
    for (final OraclePackage pkg : rows.packages().values()) {
      final List<String> matches = new ArrayList<>();
      if (exact.contains(pkg.id())) {
        matches.add(pkg.id());
      }
      for (final String prefix : prefixes) {
        if (pkg.qualifiedName().equals(prefix) || pkg.qualifiedName().startsWith(prefix + ".")) {
          matches.add("pfx:" + prefix);
        }
      }
      if (matches.size() != 1) {
        violations++;
        continue;
      }
      groupOf.put(pkg.id(), matches.getFirst());
      members.merge(matches.getFirst(), 1L, Long::sum);
    }
    final Map<List<String>, Long> pairs = new HashMap<>();
    for (final OracleEdge edge : rows.edges()) {
      final String source = groupOf.get(edge.source());
      final String target = groupOf.get(edge.target());
      if (source == null || target == null) {
        violations++;
        continue;
      }
      if (!source.equals(target)) {
        pairs.merge(List.of(source, target), edge.weight(), Long::sum);
      }
    }
    final List<Map.Entry<List<String>, Long>> ranked = new ArrayList<>(pairs.entrySet());
    ranked.sort(
        Comparator.<Map.Entry<List<String>, Long>>comparingLong(Map.Entry::getValue)
            .reversed()
            .thenComparing(entry -> entry.getKey().get(0), GraphReadsSpikeIT::compareCodePoints)
            .thenComparing(entry -> entry.getKey().get(1), GraphReadsSpikeIT::compareCodePoints));
    final Set<String> expectedKept = new HashSet<>();
    for (final Map.Entry<List<String>, Long> entry :
        ranked.subList(0, Math.min(budgetEdges, ranked.size()))) {
      expectedKept.add(edgeKey(entry.getKey().get(0), entry.getKey().get(1), entry.getValue()));
    }
    final Set<String> kept = new HashSet<>();
    long weightKept = 0;
    for (final JsonNode edge : slice.path("edges")) {
      kept.add(
          edgeKey(
              edge.path("source").asText(),
              edge.path("target").asText(),
              edge.path("weight").asLong()));
      weightKept += edge.path("weight").asLong();
    }
    final long weightTotal = pairs.values().stream().mapToLong(Long::longValue).sum();
    String largestGroup = null;
    long largestMembers = 0;
    for (final Map.Entry<String, Long> group : members.entrySet()) {
      if (group.getKey().startsWith("pfx:")
          && (group.getValue() > largestMembers
              || (group.getValue() == largestMembers
                  && compareCodePoints(group.getKey(), largestGroup) < 0))) {
        largestGroup = group.getKey();
        largestMembers = group.getValue();
      }
    }
    return new Level1(
        slice.path("aggregated").asBoolean(),
        slice.path("truncated").asBoolean(),
        slice.path("nodes").size(),
        prefixes.size(),
        exact.size(),
        slice.path("edges").size(),
        new Counts(
            slice.path("totals").path("nodes").asLong(),
            slice.path("totals").path("edges").asLong()),
        new Counts(
            slice.path("budget").path("nodes").asLong(),
            slice.path("budget").path("edges").asLong()),
        largestGroup,
        largestMembers,
        pairs.size(),
        weightTotal,
        weightKept,
        weightTotal == 0 ? 1.0 : (double) weightKept / weightTotal,
        violations,
        kept.equals(expectedKept),
        frameBytes);
  }

  private static String edgeKey(String source, String target, long weight) {
    return source + "\u0000" + target + "\u0000" + weight;
  }

  private static TypeLevel typeLevel(Sweep sweep, Oracle oracle) {
    final Rows rows = oracle.rows();
    long truncated = 0;
    long aggregated = 0;
    long totalsMismatches = 0;
    long typesSum = 0;
    long pairsSum = 0;
    String largestId = null;
    long largestTypes = -1;
    String maxPairsId = null;
    long maxPairs = -1;
    String biggestFrameId = null;
    TypeSlice biggestFrame = null;
    for (final Map.Entry<String, TypeSlice> entry : sweep.typeSlices().entrySet()) {
      final String id = entry.getKey();
      final TypeSlice slice = entry.getValue();
      final OraclePackage pkg = rows.packages().get(id);
      final long sqlTypes = pkg == null ? -1 : pkg.types();
      final long sqlPairs = rows.intraPairs().getOrDefault(id, 0L);
      truncated += slice.truncated() ? 1 : 0;
      aggregated += slice.aggregated() ? 1 : 0;
      if (slice.totalsNodes() != sqlTypes
          || slice.totalsEdges() != sqlPairs
          || slice.nodes() != slice.totalsNodes()
          || slice.edges() != slice.totalsEdges()) {
        totalsMismatches++;
      }
      typesSum += slice.totalsNodes();
      pairsSum += slice.totalsEdges();
      if (slice.totalsNodes() > largestTypes
          || (slice.totalsNodes() == largestTypes && compareCodePoints(id, largestId) < 0)) {
        largestId = id;
        largestTypes = slice.totalsNodes();
      }
      if (slice.totalsEdges() > maxPairs
          || (slice.totalsEdges() == maxPairs && compareCodePoints(id, maxPairsId) < 0)) {
        maxPairsId = id;
        maxPairs = slice.totalsEdges();
      }
      if (biggestFrame == null || slice.frameBytes() > biggestFrame.frameBytes()) {
        biggestFrameId = id;
        biggestFrame = slice;
      }
    }
    return new TypeLevel(
        sweep.typeSlices().size(),
        truncated,
        aggregated,
        totalsMismatches,
        largestId,
        largestTypes,
        maxPairsId,
        maxPairs,
        typesSum,
        pairsSum,
        biggestFrameId,
        biggestFrame == null ? 0 : biggestFrame.frameBytes(),
        biggestFrame == null ? 0 : biggestFrame.nodes(),
        biggestFrame == null ? 0 : biggestFrame.edges());
  }

  private static Outline outline(Sweep sweep, Oracle oracle) {
    final Rows rows = oracle.rows();
    final List<String> rootIds =
        sweep.root().items().stream().map(item -> item.path("id").asText()).toList();
    final boolean rootExact =
        rootIds.size() == rows.packages().size()
            && new HashSet<>(rootIds).size() == rootIds.size()
            && rows.packages().keySet().containsAll(rootIds);
    long childCountMismatches = 0;
    for (final JsonNode item : sweep.root().items()) {
      final OraclePackage pkg = rows.packages().get(item.path("id").asText());
      if (pkg == null || item.path("childCount").asLong() != pkg.types()) {
        childCountMismatches++;
      }
    }
    long typeListMismatches = 0;
    long typesListed = 0;
    long pages = sweep.root().pages();
    int longestCursor = sweep.root().longestCursor();
    for (final Map.Entry<String, Walked> entry : sweep.children().entrySet()) {
      final Walked walked = entry.getValue();
      final List<String> ids =
          walked.items().stream().map(item -> item.path("id").asText()).toList();
      final Set<String> expected = rows.types().getOrDefault(entry.getKey(), Set.of());
      if (ids.size() != expected.size()
          || new HashSet<>(ids).size() != ids.size()
          || !expected.containsAll(ids)) {
        typeListMismatches++;
      }
      typesListed += ids.size();
      pages += walked.pages();
      longestCursor = Math.max(longestCursor, walked.longestCursor());
    }
    return new Outline(
        rootIds.size(),
        sweep.root().pages(),
        rootExact,
        childCountMismatches,
        sweep.children().size(),
        typesListed,
        typeListMismatches,
        pages,
        longestCursor);
  }

  private static Determinism determinism(List<RunResult> runs, List<Latency> latency) {
    final Transcript first = runs.get(0).transcript();
    final Transcript second = runs.get(1).transcript();
    return new Determinism(
        first.sha256().equals(second.sha256()) && first.bytes() == second.bytes(),
        first.frames(),
        first.bytes(),
        first.sha256(),
        second.sha256(),
        latency.stream().allMatch(Latency::identicalResponses));
  }

  private static Deltas deltas(
      String name, Selection selection, List<RunResult> runs, Baselines baselines) {
    final long walPeak = runs.stream().mapToLong(run -> run.wal().peakBytes()).max().orElseThrow();
    final long walBaseline = baselines.walPeak(name, selection);
    final long cache = runs.get(0).cacheBytes();
    final long baselineCache = baselines.cacheBytes(name, selection);
    final long referenceCache = REFERENCE_CACHE_BYTES.get(key(name, selection));
    final double measuredStageMedian =
        median(runs.stream().mapToLong(run -> run.structuralGraph().durationMs()).toArray());
    final double baselineStageMedian = median(baselines.graphDurations(name, selection));
    return new Deltas(
        new Delta(WAL_BASELINE + " walPeakBytes", walBaseline, walPeak, walPeak - walBaseline),
        new Delta(
            STAGES_BASELINE + " cacheBytes.median", baselineCache, cache, cache - baselineCache),
        new Delta("embedded reference cache", referenceCache, cache, cache - referenceCache),
        new StageDelta(
            baselineStageMedian, measuredStageMedian, measuredStageMedian - baselineStageMedian));
  }

  private static List<Expectation> expectations(
      List<RepositoryResult> repositories, Baselines baselines) {
    final Checks checks = new Checks();
    for (final RepositoryResult repository : repositories) {
      for (final SelectionResult result : repository.selections()) {
        final String name = repository.name();
        final Selection selection = Selection.valueOf(result.selection());
        final String at = name + "/" + selection.name();
        extraction(checks, at, name, selection, result, baselines);
        storage(checks, at, name, selection, result);
        level1Checks(checks, at, name, selection, result, baselines);
        for (final Latency call : result.latency()) {
          checks.that(
              "E6/" + at + "/" + call.request(),
              "warm median of " + WARM_RUNS + " after the first call < 100 ms",
              "< " + LATENCY_BUDGET_MICROS + " us",
              call.warmMedianMicros() + " us",
              call.warmMedianMicros() < LATENCY_BUDGET_MICROS);
        }
        checks.that(
            "E7/" + at + "/level1",
            "the level-1 frame fits the 8 MiB soft budget",
            "<= " + SOFT_RESPONSE_BYTES,
            result.level1().frameBytes(),
            result.level1().frameBytes() <= SOFT_RESPONSE_BYTES);
        checks.that(
            "E7/" + at + "/largestType",
            "the largest TYPE frame of the selection fits the 8 MiB soft budget",
            "<= " + SOFT_RESPONSE_BYTES,
            result.typeLevel().largestFrameBytes()
                + " ("
                + result.typeLevel().largestFrameId()
                + ")",
            result.typeLevel().largestFrameBytes() <= SOFT_RESPONSE_BYTES);
        checks.equal(
            "E8/" + at + "/transcripts",
            "the second analysis's sweep is byte-identical to the first's",
            result.determinism().firstSha256(),
            result.determinism().secondSha256());
        checks.equal(
            "E8/" + at + "/repeats",
            "every timed request answered the same payload six times",
            true,
            result.determinism().repeatsIdentical());
        typeChecks(checks, at, name, selection, result);
        outlineChecks(checks, at, name, selection, result, baselines);
      }
    }
    return checks.list();
  }

  private static void extraction(
      Checks checks,
      String at,
      String name,
      Selection selection,
      SelectionResult result,
      Baselines baselines) {
    final JsonNode configuration = baselines.configuration(name, selection);
    final JsonNode graphCounters = baselines.graphCounters(name, selection);
    for (final RunResult run : result.runs()) {
      final String where = "E1/" + at + "/run" + run.run();
      final ReadBack readBack = run.readBack();
      checks.equal(
          where + "/elements",
          "elements equal stages-baseline.json",
          configuration.path("elements"),
          tree(readBack.elements()));
      checks.equal(
          where + "/dependencies",
          "dependency, evidence and module rows equal stages-baseline.json",
          configuration.path("dependencies"),
          tree(readBack.dependencies()));
      checks.equal(
          where + "/metricRows",
          "code_metric rows equal stages-baseline.json",
          configuration.path("metricRows"),
          tree(readBack.metricRows()));
      for (final AnalysisStage stage :
          List.of(
              AnalysisStage.REPOSITORY_VALIDATION,
              AnalysisStage.LAYOUT_DISCOVERY,
              AnalysisStage.SOURCE_ANALYSIS)) {
        checks.equal(
            where + "/" + stage.name() + ".counters",
            stage.name() + " counters equal stages-baseline.json",
            baselines.stageCounters(configuration, stage.name()),
            run.counters().get(stage.name()));
      }
      checks.equal(
          where + "/STRUCTURAL_GRAPH.counters",
          "the thirteen STRUCTURAL_GRAPH counters equal pagerank-determinism.json",
          graphCounters,
          run.structuralGraph().counters());
      checks.equal(
          where + "/STRUCTURAL_GRAPH.status",
          "STRUCTURAL_GRAPH COMPLETED, the analysis analysis.completed",
          "COMPLETED " + ANALYSIS_COMPLETED,
          run.structuralGraph().status() + " " + run.terminalEvent());
      checks.equal(
          where + "/STRUCTURAL_GRAPH.row",
          "the analysis_stage row equals the stage.completed event",
          true,
          run.structuralGraph().rowMatchesEvent());
    }
  }

  private static void storage(
      Checks checks, String at, String name, Selection selection, SelectionResult result) {
    final Deltas deltas = result.deltas();
    checks.that(
        "E2/" + at + "/walPeakBytes",
        "the largest -wal seen in either analysis <= 64 MiB",
        "<= " + WAL_CEILING_BYTES,
        deltas.walVsBaseline().measured(),
        deltas.walVsBaseline().measured() <= WAL_CEILING_BYTES);
    checks.that(
        "E3/" + at + "/cacheBytes",
        "the closed cache is smaller than the reference cache",
        "< " + deltas.cacheVsReference().baseline(),
        deltas.cacheVsReference().measured(),
        deltas.cacheVsReference().measured() < deltas.cacheVsReference().baseline());
    if (LARGEST.equals(name) && selection == Selection.ALL) {
      checks.that(
          "E4/" + at + "/STRUCTURAL_GRAPH.durationMs",
          "median of two analyses at most 100 ms above pagerank-determinism.json's median of two",
          "<= +" + STAGE_DELTA_BUDGET_MILLIS,
          deltas.structuralGraph().deltaMillis(),
          deltas.structuralGraph().deltaMillis() <= STAGE_DELTA_BUDGET_MILLIS);
    }
  }

  private static void level1Checks(
      Checks checks,
      String at,
      String name,
      Selection selection,
      SelectionResult result,
      Baselines baselines) {
    final Level1 level1 = result.level1();
    final Rollup rollup = ROLLUPS.get(key(name, selection));
    final String where = "E5/" + at;
    checks.equal(
        where + "/partition",
        "every package of the node set falls in exactly one level-1 node",
        0L,
        level1.partitionViolations());
    checks.equal(
        where + "/keptEdges",
        "the kept edges are the budget.edges heaviest group edges derived from package_edge",
        true,
        level1.keptEqualsOracle());
    if (rollup != null) {
      checks.equal(
          where + "/flags",
          "aggregated, " + rollup.groups() + " nodes, truncated",
          "aggregated=true nodes=" + rollup.groups() + " truncated=true",
          "aggregated="
              + level1.aggregated()
              + " nodes="
              + level1.nodes()
              + " truncated="
              + level1.truncated());
      checks.equal(
          where + "/groupEdges",
          "group edges before the cut equal the reference greedy split",
          rollup.groupEdges(),
          level1.groupEdgesBeforeCut());
      checks.equal(
          where + "/largestGroup",
          "the largest group's packages equal the reference",
          (long) rollup.largestGroup(),
          level1.largestGroupPackages());
      checks.equal(
          where + "/singletonGroups",
          "groups of one package (pkg: nodes) equal the reference",
          rollup.singletonGroups(),
          level1.packageNodes());
      checks.equal(
          where + "/weightShareKept",
          "the weight share the cut keeps, to four places, equals the reference",
          rollup.weightShareKept(),
          Math.round(level1.weightShareKept() * SHARE_SCALE) / SHARE_SCALE);
    } else {
      final JsonNode counters = baselines.graphCounters(name, selection);
      checks.equal(
          where + "/flags",
          "neither aggregated nor truncated",
          "aggregated=false truncated=false",
          "aggregated=" + level1.aggregated() + " truncated=" + level1.truncated());
      checks.equal(
          where + "/nodes",
          "every package is a node (packageNodes)",
          counters.path("packageNodes").asLong(),
          (long) level1.nodes());
      checks.equal(
          where + "/edges",
          "every package edge is an edge (packageEdges)",
          counters.path("packageEdges").asLong(),
          (long) level1.edges());
    }
  }

  private static void typeChecks(
      Checks checks, String at, String name, Selection selection, SelectionResult result) {
    final TypeLevel type = result.typeLevel();
    final TypeCensus census = TYPE_CENSUS.get(key(name, selection));
    final String where = "E9/" + at;
    checks.equal(
        where + "/truncated",
        "no TYPE slice is truncated or aggregated on the corpus",
        "0 0",
        type.truncated() + " " + type.aggregated());
    checks.equal(
        where + "/totals",
        "every TYPE slice's totals equal the SQL type and intra-pair counts, all of them drawn",
        0L,
        type.totalsMismatches());
    checks.equal(
        where + "/largest",
        "the largest package by types equals the reference",
        census.largestId() + " " + census.largestTypes(),
        type.largestId() + " " + type.largestTypes());
    checks.equal(
        where + "/maxIntraPairs",
        "the most intra-package pairs equal the reference",
        census.maxIntraPairs(),
        type.maxIntraPairs());
    checks.equal(
        where + "/sums",
        "Σ types and Σ intra pairs over the packages equal the reference",
        census.typesSum() + " " + census.intraPairsSum(),
        type.typesSum() + " " + type.intraPairsSum());
  }

  private static void outlineChecks(
      Checks checks,
      String at,
      String name,
      Selection selection,
      SelectionResult result,
      Baselines baselines) {
    final Outline outline = result.outline();
    final String where = "E10/" + at;
    checks.equal(
        where + "/root",
        "the root lists each package of the node set once",
        baselines.graphCounters(name, selection).path("packageNodes").asLong() + " exact",
        outline.rootItems() + (outline.rootExact() ? " exact" : " not exact"));
    checks.equal(
        where + "/childCount",
        "every root item's childCount equals the SQL count of its internal types",
        0L,
        outline.childCountMismatches());
    checks.equal(
        where + "/children",
        "every package's pages list each of its internal types once",
        0L,
        outline.typeListMismatches());
    checks.that(
        where + "/cursor",
        "every cursor <= 256 characters",
        "<= " + CURSOR_CEILING_CHARS,
        outline.longestCursor(),
        outline.longestCursor() <= CURSOR_CEILING_CHARS);
  }

  private static JsonNode tree(Object value) {
    try {
      return JSON.readTree(JSON.writeValueAsBytes(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static final class Checks {

    private final List<Expectation> list = new ArrayList<>();

    void equal(String id, String claim, Object expected, Object measured) {
      list.add(
          new Expectation(
              id,
              claim,
              String.valueOf(expected),
              String.valueOf(measured),
              Objects.equals(expected, measured)));
    }

    void that(String id, String claim, String expected, Object measured, boolean met) {
      list.add(new Expectation(id, claim, expected, String.valueOf(measured), met));
    }

    List<Expectation> list() {
      return List.copyOf(list);
    }
  }

  private record Baselines(JsonNode stages, JsonNode graph, JsonNode wal) {

    JsonNode configuration(String name, Selection selection) {
      return find(
          find(stages.path("repositories"), "name", name).path("configurations"),
          "selection",
          selection.name());
    }

    JsonNode stageCounters(JsonNode configuration, String stage) {
      return find(configuration.path("stages"), "stage", stage).path("counters");
    }

    long cacheBytes(String name, Selection selection) {
      return configuration(name, selection).path("cacheBytes").path("median").asLong();
    }

    private JsonNode graphSelection(String name, Selection selection) {
      return find(
          find(graph.path("repositories"), "name", name).path("selections"),
          "selection",
          selection.name());
    }

    JsonNode graphCounters(String name, Selection selection) {
      return graphSelection(name, selection).path("runs").get(0).path("counters");
    }

    long[] graphDurations(String name, Selection selection) {
      final List<Long> durations = new ArrayList<>();
      graphSelection(name, selection)
          .path("runs")
          .forEach(run -> durations.add(run.path("durationMs").asLong()));
      return durations.stream().mapToLong(Long::longValue).toArray();
    }

    long walPeak(String name, Selection selection) {
      return find(
              find(wal.path("repositories"), "name", name).path("selections"),
              "selection",
              selection.name())
          .path("write")
          .path("walPeakBytes")
          .asLong();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
      for (final JsonNode element : array) {
        if (value.equals(element.path(field).asText())) {
          return element;
        }
      }
      throw new AssertionError("a linha de base não tem " + field + " = " + value);
    }
  }

  private static final class WalSampler implements AutoCloseable {

    private final Path repositories;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile long peakBytes;
    private volatile long peakNanos;
    private volatile long samples;
    private volatile Path wal;

    private WalSampler(Path repositories) {
      this.repositories = repositories;
      this.thread = new Thread(this::loop, "graph-reads-wal-sampler");
      this.thread.setDaemon(true);
    }

    static WalSampler start(Path repositories) {
      final WalSampler sampler = new WalSampler(repositories);
      sampler.thread.start();
      return sampler;
    }

    private void loop() {
      while (running.get()) {
        sample();
        LockSupport.parkNanos(WAL_SAMPLE_NANOS);
      }
    }

    private void sample() {
      try {
        if (wal == null) {
          wal = locate();
          if (wal == null) {
            return;
          }
        }
        final long size = Files.size(wal);
        samples++;
        if (size > peakBytes) {
          peakBytes = size;
          peakNanos = System.nanoTime();
        }
      } catch (NoSuchFileException notYet) {
      } catch (IOException unreadable) {
        throw new IllegalStateException("o tamanho do -wal não pôde ser lido", unreadable);
      }
    }

    private Path locate() throws IOException {
      if (!Files.isDirectory(repositories)) {
        return null;
      }
      try (Stream<Path> keys = Files.list(repositories)) {
        return keys.map(key -> key.resolve("analysis.sqlite-wal")).findFirst().orElse(null);
      }
    }

    WalMeasure stop() throws InterruptedException {
      running.set(false);
      thread.join();
      sample();
      final long finalBytes;
      try {
        finalBytes = wal == null ? 0 : Files.size(wal);
      } catch (IOException unreadable) {
        throw new IllegalStateException("o tamanho do -wal não pôde ser lido", unreadable);
      }
      return new WalMeasure(peakBytes, peakNanos, finalBytes, samples);
    }

    @Override
    public void close() {
      running.set(false);
      try {
        thread.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private record WalMeasure(long peakBytes, long peakNanos, long finalBytes, long samples) {}

  private record GcTotals(long collections, long millis) {

    static GcTotals now() {
      long collections = 0;
      long millis = 0;
      for (final GarbageCollectorMXBean collector :
          ManagementFactory.getGarbageCollectorMXBeans()) {
        collections += Math.max(0, collector.getCollectionCount());
        millis += Math.max(0, collector.getCollectionTime());
      }
      return new GcTotals(collections, millis);
    }
  }

  private static int compareCodePoints(String left, String right) {
    if (right == null) {
      return left == null ? 0 : -1;
    }
    if (left == null) {
      return 1;
    }
    return Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  private static double median(long[] values) {
    final long[] sorted = values.clone();
    Arrays.sort(sorted);
    final int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
  }

  private static List<String> repositoriesToMeasure() {
    final String only = System.getProperty("strata.graphReads.only");
    if (only == null || only.isBlank()) {
      return REPOSITORIES;
    }
    final List<String> chosen = List.of(only.split(","));
    assertThat(REPOSITORIES).as("-Dstrata.graphReads.only").containsAll(chosen);
    return REPOSITORIES.stream().filter(chosen::contains).toList();
  }

  private static double loadAverage() {
    return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
  }

  private static Settings settings(double loadAtStart, Double loadAtEnd) {
    final Map<String, Object> constants = new LinkedHashMap<>();
    constants.put("GRAPH_SLICE_PACKAGE_MAX_NODES", AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_NODES);
    constants.put("GRAPH_SLICE_PACKAGE_MAX_EDGES", AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES);
    constants.put("GRAPH_SLICE_TYPE_MAX_NODES", AnalysisConstants.GRAPH_SLICE_TYPE_MAX_NODES);
    constants.put("GRAPH_SLICE_TYPE_MAX_EDGES", AnalysisConstants.GRAPH_SLICE_TYPE_MAX_EDGES);
    constants.put("OUTLINE_PAGE_DEFAULT", AnalysisConstants.OUTLINE_PAGE_DEFAULT);
    constants.put("OUTLINE_PAGE_MAX", AnalysisConstants.OUTLINE_PAGE_MAX);
    constants.put("SEARCH_RESULTS_MAX", AnalysisConstants.SEARCH_RESULTS_MAX);
    constants.put("MAX_CURSOR_CHARS", AnalysisConstants.MAX_CURSOR_CHARS);
    constants.put("MAX_FRAME_BYTES", AnalysisConstants.MAX_FRAME_BYTES);
    constants.put("SOFT_RESPONSE_BYTES", AnalysisConstants.SOFT_RESPONSE_BYTES);
    constants.put(
        "SQLITE_JOURNAL_SIZE_LIMIT_BYTES", AnalysisConstants.SQLITE_JOURNAL_SIZE_LIMIT_BYTES);
    constants.put("SQLITE_CACHE_SIZE_KIB", AnalysisConstants.SQLITE_CACHE_SIZE_KIB);
    final Map<String, Object> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.payload());
    }
    final Map<String, Object> requests = new LinkedHashMap<>();
    requests.put("graph.slice PACKAGE", "{analysisId, level: PACKAGE}");
    requests.put("graph.slice TYPE", "{analysisId, level: TYPE, parentId}");
    requests.put(
        "outline.children", "{analysisId, cursor?, limit: " + OUTLINE_PAGE_LIMIT + ", parentId}");
    requests.put("graph.search", "{analysisId, kinds: " + SEARCH_KINDS + ", query}");
    final Map<String, Object> baselines = new LinkedHashMap<>();
    baselines.put("extraction", STAGES_BASELINE);
    baselines.put("graphCountersAndDuration", GRAPH_BASELINE);
    baselines.put("walPeakBytes", WAL_BASELINE + " (the last benchmark that recorded it)");
    baselines.put(
        "rollupAndTypeCensus",
        "level-1 aggregation and per-package type counts from an earlier analysis of the corpus,"
            + " embedded as literals");
    baselines.put(
        "referenceCaches", "reference caches, closed, empty -wal; sizes embedded as literals");
    final Map<String, Object> load = new LinkedHashMap<>();
    load.put("start", loadAtStart);
    load.put("end", loadAtEnd);
    return new Settings(
        constants,
        "Stages.production()",
        selections,
        requests,
        QUERIES,
        WARM_RUNS,
        RUNS,
        WAL_SAMPLE_NANOS / NANOS_PER_MICRO,
        new Budgets(
            LATENCY_BUDGET_MICROS,
            WAL_CEILING_BYTES,
            SOFT_RESPONSE_BYTES,
            STAGE_DELTA_BUDGET_MILLIS),
        baselines,
        "scripts/corpus.sh's pinned clones, read-only, through -Dstrata.corpus",
        load);
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

  private static Path corpusDirectory() throws IOException {
    final Path corpus = Path.of(System.getProperty("strata.corpus"));
    assertThat(corpus.resolve("broken-tree"))
        .as("o corpus não foi clonado: rode bash scripts/corpus.sh antes do spike")
        .isDirectory();
    return corpus.toRealPath();
  }

  private static String headOf(Path checkout) throws IOException {
    final File root = checkout.toFile();
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder().addCeilingDirectory(root.getParentFile()).findGitDir(root);
    assertThat(builder.getGitDir()).as("não é um checkout Git: " + root).isNotNull();
    try (Repository repository = builder.setMustExist(true).build()) {
      return Optional.ofNullable(repository.resolve("HEAD"))
          .map(head -> head.name())
          .orElse("unborn");
    }
  }

  private static String key(String name, Selection selection) {
    return name + "/" + selection.name();
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream in =
        new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
      in.transferTo(OutputStream.nullOutputStream());
      return HEX.formatHex(((DigestInputStream) in).getMessageDigest().digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void write(Path report, Report content) throws IOException {
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

  private record Report(
      int schema,
      String measuredAt,
      String baseCommit,
      String measure,
      Settings settings,
      Environment environment,
      List<Expectation> expectations,
      List<RepositoryResult> repositories) {}

  private record Settings(
      Map<String, Object> constants,
      String plan,
      Map<String, Object> selections,
      Map<String, Object> requests,
      List<String> queries,
      int warmRuns,
      int analysesPerSelection,
      long walSampleIntervalMicros,
      Budgets budgets,
      Map<String, Object> baselines,
      String corpus,
      Map<String, Object> loadAverage1m) {}

  private record Budgets(
      long latencyWarmMedianMicros,
      long walPeakBytes,
      long softResponseBytes,
      double structuralGraphDeltaMillis) {}

  private record Expectation(
      String id, String claim, String expected, String measured, boolean met) {}

  private record RepositoryResult(String name, String commitId, List<SelectionResult> selections) {}

  private record SelectionResult(
      String selection,
      Map<String, Object> payload,
      List<RunResult> runs,
      ReadBack readBack,
      Level1 level1,
      TypeLevel typeLevel,
      Outline outline,
      List<SearchResult> search,
      List<Latency> latency,
      ReadPhase readPhase,
      Determinism determinism,
      Deltas deltas) {}

  private record RunResult(
      int run,
      String analysisId,
      String terminalEvent,
      long analysisWallMillis,
      Map<String, Long> stageDurationsMs,
      StructuralGraph structuralGraph,
      Wal wal,
      long cacheBytes,
      String keptCache,
      Load loadAverage1m,
      Transcript transcript,
      @JsonIgnore Map<String, JsonNode> counters,
      @JsonIgnore ReadBack readBack) {

    static RunResult of(
        int run,
        AnalysisId id,
        List<Frame> frames,
        WalMeasure wal,
        long cacheBytes,
        Load load,
        Transcript transcript,
        ReadBack readBack,
        String keptCache) {
      final long started =
          frames.stream()
              .filter(frame -> "analysis.started".equals(frame.event()))
              .findFirst()
              .orElseThrow()
              .nanos();
      final Frame terminal = frames.stream().filter(Frame::isTerminal).findFirst().orElseThrow();
      final Map<String, Long> durations = new LinkedHashMap<>();
      final Map<String, JsonNode> counters = new LinkedHashMap<>();
      JsonNode graph = null;
      for (final Frame frame : frames) {
        if (STAGE_COMPLETED.equals(frame.event())) {
          durations.put(frame.stage(), frame.payload().path("durationMs").asLong());
          counters.put(frame.stage(), frame.payload().path("counters"));
          if (GRAPH_STAGE.equals(frame.stage())) {
            graph = frame.payload();
          }
        }
      }
      Objects.requireNonNull(graph, "nenhum stage.completed do STRUCTURAL_GRAPH");
      final StageRow row = readBack.stages().get(GRAPH_STAGE);
      final boolean rowMatches =
          row != null
              && row.status().equals(graph.path("status").asText())
              && row.durationMs() == graph.path("durationMs").asLong()
              && row.counters().equals(graph.path("counters"));
      Frame before = null;
      for (final Frame frame : frames) {
        if (frame.nanos() <= wal.peakNanos()) {
          before = frame;
        }
      }
      return new RunResult(
          run,
          id.value(),
          terminal.event(),
          (terminal.nanos() - started) / NANOS_PER_MILLI,
          durations,
          new StructuralGraph(
              graph.path("status").asText(),
              graph.path("durationMs").asLong(),
              graph.path("counters"),
              rowMatches),
          new Wal(
              wal.peakBytes(),
              wal.finalBytes(),
              wal.samples(),
              wal.peakNanos() == 0 ? null : (wal.peakNanos() - started) / NANOS_PER_MILLI,
              before == null ? null : (before.event() + " " + before.stage()).trim()),
          cacheBytes,
          keptCache,
          load,
          transcript,
          counters,
          readBack);
    }
  }

  private record StructuralGraph(
      String status, long durationMs, JsonNode counters, boolean rowMatchesEvent) {}

  private record Wal(
      long peakBytes, long finalBytes, long samples, Long peakAtMillis, String peakAfter) {}

  private record Load(double start, double end) {}

  private record Transcript(String file, long frames, long bytes, String sha256) {}

  private record StageRow(String status, long durationMs, JsonNode counters) {}

  private record ReadBack(
      Elements elements,
      Dependencies dependencies,
      Map<String, Long> metricRows,
      @JsonIgnore Map<String, StageRow> stages) {}

  private record Counts(long nodes, long edges) {}

  private record Level1(
      boolean aggregated,
      boolean truncated,
      int nodes,
      int prefixNodes,
      int packageNodes,
      int edges,
      Counts totals,
      Counts budget,
      String largestGroupId,
      long largestGroupPackages,
      long groupEdgesBeforeCut,
      long weightTotal,
      long weightKept,
      double weightShareKept,
      long partitionViolations,
      boolean keptEqualsOracle,
      long frameBytes) {}

  private record TypeLevel(
      long slices,
      long truncated,
      long aggregated,
      long totalsMismatches,
      String largestId,
      long largestTypes,
      String maxIntraPairsId,
      long maxIntraPairs,
      long typesSum,
      long intraPairsSum,
      String largestFrameId,
      long largestFrameBytes,
      int largestFrameNodes,
      int largestFrameEdges) {}

  private record Outline(
      long rootItems,
      long rootPages,
      boolean rootExact,
      long childCountMismatches,
      long packages,
      long typesListed,
      long typeListMismatches,
      long pages,
      int longestCursor) {}

  private record SearchResult(String query, int results, long frameBytes, String firstElementId) {}

  private record Latency(
      String request,
      String method,
      int page,
      long firstCallMicros,
      long warmMedianMicros,
      long warmMaxMicros,
      List<Long> warmRunsMicros,
      long frameBytes,
      boolean identicalResponses,
      @JsonIgnore JsonNode payload) {}

  private record ReadPhase(long gcCollectionsDuringTimed, long gcMillisDuringTimed, Load load) {}

  private record Determinism(
      boolean transcriptsIdentical,
      long frames,
      long bytes,
      String firstSha256,
      String secondSha256,
      boolean repeatsIdentical) {}

  private record Delta(String baselineSource, long baseline, long measured, long delta) {}

  private record StageDelta(
      double baselineMedianMillis, double measuredMedianMillis, double deltaMillis) {}

  private record Deltas(
      Delta walVsBaseline,
      Delta cacheVsBaseline,
      Delta cacheVsReference,
      StageDelta structuralGraph) {}

  private record Reads(List<Latency> latency, Sweep sweep, ReadPhase readPhase) {}

  private record Analyzed(RunResult run, Reads reads, Oracle oracle) {}
}
