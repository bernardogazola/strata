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
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class ElementReadsSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;
  private static final String REPORT = "benchmarks/element-reads.json";
  private static final String GRAPH_READS = "benchmarks/graph-reads.json";
  private static final String WORK = "analyzer/target/element-reads";
  private static final String ANALYZER_VERSION = "0.0.0-spike";
  private static final String ANALYSIS_COMPLETED = "analysis.completed";
  private static final String STAGE_COMPLETED = "analysis.stage.completed";
  private static final String GRAPH_STAGE = AnalysisStage.STRUCTURAL_GRAPH.name();
  private static final int RUNS = 2;
  private static final int WARM_RUNS = 9;
  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HexFormat HEX = HexFormat.of();

  private static final long WARM_BUDGET_MICROS = 65_000L;

  private static final long RNF05_MICROS = 100_000L;

  private static final long REFERENCE_WORST_WARM_MICROS = 29_176L;

  private static final long DESCRIBE_FRAME_BUDGET = 160_000L;

  private static final long PAGE_500_FRAME_BUDGET = 300_000L;
  private static final long PAGE_2000_FRAME_BUDGET = 1_100_000L;
  private static final long EVIDENCE_FRAME_BUDGET = 80_000L;

  private static final long SOFT_RESPONSE_BYTES = AnalysisConstants.SOFT_RESPONSE_BYTES;

  private static final int SWEEP_LIMIT = 1;

  private static final List<Integer> CHAIN_LIMITS =
      List.of(AnalysisConstants.DEPENDENCY_PAGE_DEFAULT, AnalysisConstants.DEPENDENCY_PAGE_MAX);

  private static final int EVIDENCE_PAIRS = 3;

  private static final int DEPENDENCY_CURSOR_CHARS = 24;

  private static final int MAX_PAGES = 1_000;

  private static final int EXAMPLES = 5;

  private static final String METHOD_PREFIX = "method:";
  private static final String PACKAGE = "PACKAGE";
  private static final String TYPE = "TYPE";
  private static final String IN = "IN";
  private static final String OUT = "OUT";

  private static final byte[] ID_PREFIX = "{\"id\":\"".getBytes(StandardCharsets.UTF_8);

  private static final int REQUEST_ID_CHARS = 30;

  private static final int AFTER_ID = ID_PREFIX.length + REQUEST_ID_CHARS + 1;

  private static final byte[] OK_INFIX =
      "\",\"kind\":\"response\",\"ok\":true,\"payload\":".getBytes(StandardCharsets.UTF_8);

  private static final byte[] OK_SUFFIX =
      (",\"protocol\":" + AnalysisConstants.PROTOCOL_VERSION + "}")
          .getBytes(StandardCharsets.UTF_8);

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", "broken-tree", "junit5", "spring-framework", "elasticsearch");

  private static final String MEASURE =
      "The inspector reads over the corpus. Per repository and selection, two analyses"
          + " through the real AnalysisRegistry (RegistrySettings.production, Stages.production())"
          + " on fresh data directories. After the terminal event, with the registry open, every"
          + " request goes through a Dispatcher built on Handlers.all and the production"
          + " JsonLines.Writer into a buffer (Lifecycle's path for one frame, without stdin and the"
          + " pipe). Both analyses: the IT's SQL census; the SWEEP, recorded as a transcript (per"
          + " internal PACKAGE and TYPE in code-point order: element.describe, then"
          + " element.dependencies IN and OUT at limit 1); then the worst requests in a second"
          + " transcript (level 1, the worst package's TYPE slice, the most-methods describe, the IN"
          + " and OUT chains to the end at limit 500 and 2000, the three most-evidenced pairs). The"
          + " second analysis asks the first's targets, and the transcripts compare byte for byte."
          + " Analysis 1 only: the IT's oracle SQL for the targets, then each worst request cold"
          + " (first call on a fresh reader: the idle readers are discarded first by read units that"
          + " throw, the production path that closes a reader after a failure) and nine times warm.";

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

  private static final String REFERENCE_CENSUS_TABLE =
      """
      commons-lang/DEFAULT | methods | type:org.apache.commons.lang3.ArrayUtils | 377
      commons-lang/DEFAULT | fanIn | type:org.apache.commons.lang3.ArrayUtils | 31
      commons-lang/DEFAULT | outTotal | type:org.apache.commons.lang3.ArrayUtils | 53
      commons-lang/DEFAULT | packageIn | pkg:org.apache.commons.lang3 | 13
      commons-lang/ALL | methods | type:org.apache.commons.lang3.ArrayUtils | 377
      commons-lang/ALL | fanIn | type:org.apache.commons.lang3.AbstractLangTest | 185
      commons-lang/ALL | outTotal | type:org.apache.commons.lang3.function.FailableTest | 79
      commons-lang/ALL | packageIn | pkg:org.apache.commons.lang3 | 16
      broken-tree/DEFAULT | methods | type:org.apache.commons.lang3.ArrayUtils | 377
      broken-tree/DEFAULT | fanIn | type:org.apache.commons.lang3.ArrayUtils | 30
      broken-tree/DEFAULT | outTotal | type:org.apache.commons.lang3.ArrayUtils | 53
      broken-tree/DEFAULT | packageIn | pkg:org.apache.commons.lang3 | 13
      broken-tree/ALL | methods | type:org.apache.commons.lang3.ArrayUtils | 377
      broken-tree/ALL | fanIn | type:org.apache.commons.lang3.AbstractLangTest | 185
      broken-tree/ALL | outTotal | type:org.apache.commons.lang3.function.FailableTest | 79
      broken-tree/ALL | packageIn | pkg:org.apache.commons.lang3 | 16
      junit5/DEFAULT | methods | type:org.junit.jupiter.api.Assertions | 291
      junit5/DEFAULT | fanIn | type:org.junit.platform.commons.util.Preconditions | 231
      junit5/DEFAULT | outTotal | type:org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor | 80
      junit5/DEFAULT | packageIn | pkg:org.junit.platform.commons.util | 53
      junit5/ALL | methods | type:org.junit.jupiter.api.Assertions | 291
      junit5/ALL | fanIn | type:org.junit.jupiter.api.Test | 1295
      junit5/ALL | outTotal | type:org.junit.jupiter.params.ParameterizedTestIntegrationTests | 102
      junit5/ALL | packageIn | pkg:org.junit.jupiter.api | 81
      spring-framework/DEFAULT | methods | type:org.springframework.http.HttpHeaders | 145
      spring-framework/DEFAULT | fanIn | type:org.springframework.util.Assert | 1709
      spring-framework/DEFAULT | outTotal | type:org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter | 135
      spring-framework/DEFAULT | packageIn | pkg:org.springframework.util | 366
      spring-framework/ALL | methods | type:org.springframework.beans.factory.DefaultListableBeanFactoryTests | 198
      spring-framework/ALL | fanIn | type:org.springframework.util.Assert | 1832
      spring-framework/ALL | outTotal | type:org.springframework.web.servlet.mvc.method.annotation.ServletAnnotationControllerHandlerMethodTests | 160
      spring-framework/ALL | packageIn | pkg:org.springframework.util | 404
      elasticsearch/DEFAULT | methods | type:org.elasticsearch.xpack.core.security.support.NoOpLogger | 376
      elasticsearch/DEFAULT | fanIn | type:org.elasticsearch.common.io.stream.StreamInput | 4633
      elasticsearch/DEFAULT | outTotal | type:org.elasticsearch.xpack.security.Security | 521
      elasticsearch/DEFAULT | packageIn | pkg:org.elasticsearch.core | 1224
      elasticsearch/ALL | methods | type:org.elasticsearch.xpack.esql.datasource.csv.CsvFormatReaderTests | 483
      elasticsearch/ALL | fanIn | type:org.elasticsearch.test.ESTestCase | 5246
      elasticsearch/ALL | outTotal | type:org.elasticsearch.xpack.security.Security | 521
      elasticsearch/ALL | packageIn | pkg:org.elasticsearch.test | 1425
      """;

  private record ReferenceCensus(Worst methods, Worst fanIn, Worst outTotal, Worst packageIn) {}

  private static final Map<String, ReferenceCensus> REFERENCE_CENSUS =
      referenceCensus(REFERENCE_CENSUS_TABLE);

  private static final String REFERENCE_COST_TABLE =
      """
      commons-lang/DEFAULT | describe | type:org.apache.commons.lang3.ArrayUtils | 461
      commons-lang/DEFAULT | IN 500 | type:org.apache.commons.lang3.ArrayUtils | 194
      commons-lang/DEFAULT | IN 2000 | type:org.apache.commons.lang3.ArrayUtils | 191
      commons-lang/DEFAULT | OUT 500 | type:org.apache.commons.lang3.ArrayUtils | 398
      commons-lang/DEFAULT | OUT 2000 | type:org.apache.commons.lang3.ArrayUtils | 381
      commons-lang/DEFAULT | package IN 500 | pkg:org.apache.commons.lang3 | 61
      commons-lang/DEFAULT | package IN 2000 | pkg:org.apache.commons.lang3 | 62
      commons-lang/DEFAULT | evidence | type:org.apache.commons.lang3.StringUtils -> type:java.lang.String | 144
      junit5/ALL | describe | type:org.junit.jupiter.api.Assertions | 573
      junit5/ALL | IN 500 | type:org.junit.jupiter.api.Test | 5599
      junit5/ALL | IN 2000 | type:org.junit.jupiter.api.Test | 4871
      junit5/ALL | OUT 500 | type:org.junit.jupiter.params.ParameterizedTestIntegrationTests | 347
      junit5/ALL | OUT 2000 | type:org.junit.jupiter.params.ParameterizedTestIntegrationTests | 345
      junit5/ALL | package IN 500 | pkg:org.junit.jupiter.api | 203
      junit5/ALL | package IN 2000 | pkg:org.junit.jupiter.api | 212
      junit5/ALL | evidence | type:org.junit.platform.console.options.TestDiscoveryOptions -> type:java.util.List | 121
      spring-framework/ALL | describe | type:org.springframework.beans.factory.DefaultListableBeanFactoryTests | 202
      spring-framework/ALL | IN 500 | type:org.springframework.util.Assert | 8197
      spring-framework/ALL | IN 2000 | type:org.springframework.util.Assert | 9151
      spring-framework/ALL | OUT 500 | type:org.springframework.web.servlet.mvc.method.annotation.ServletAnnotationControllerHandlerMethodTests | 641
      spring-framework/ALL | OUT 2000 | type:org.springframework.web.servlet.mvc.method.annotation.ServletAnnotationControllerHandlerMethodTests | 661
      spring-framework/ALL | package IN 500 | pkg:org.springframework.util | 930
      spring-framework/ALL | package IN 2000 | pkg:org.springframework.util | 941
      spring-framework/ALL | evidence | type:org.springframework.http.HttpHeaders -> type:java.lang.String | 126
      elasticsearch/DEFAULT | describe | type:org.elasticsearch.xpack.core.security.support.NoOpLogger | 363
      elasticsearch/DEFAULT | IN 500 | type:org.elasticsearch.common.io.stream.StreamInput | 28771
      elasticsearch/DEFAULT | IN 2000 | type:org.elasticsearch.common.io.stream.StreamInput | 29176
      elasticsearch/DEFAULT | OUT 500 | type:org.elasticsearch.xpack.security.Security | 2706
      elasticsearch/DEFAULT | OUT 2000 | type:org.elasticsearch.xpack.security.Security | 2678
      elasticsearch/DEFAULT | package IN 500 | pkg:org.elasticsearch.core | 1732
      elasticsearch/DEFAULT | package IN 2000 | pkg:org.elasticsearch.core | 2985
      elasticsearch/DEFAULT | evidence | type:org.elasticsearch.gradle.testclusters.ElasticsearchNode -> type:java.lang.String | 127
      elasticsearch/ALL | describe | type:org.elasticsearch.xpack.esql.datasource.csv.CsvFormatReaderTests | 417
      elasticsearch/ALL | IN 500 | type:org.elasticsearch.test.ESTestCase | 27558
      elasticsearch/ALL | IN 2000 | type:org.elasticsearch.test.ESTestCase | 28605
      elasticsearch/ALL | OUT 500 | type:org.elasticsearch.xpack.security.Security | 2720
      elasticsearch/ALL | OUT 2000 | type:org.elasticsearch.xpack.security.Security | 2665
      elasticsearch/ALL | package IN 500 | pkg:org.elasticsearch.test | 1376
      elasticsearch/ALL | package IN 2000 | pkg:org.elasticsearch.test | 2502
      elasticsearch/ALL | evidence | type:org.elasticsearch.gradle.testclusters.ElasticsearchNode -> type:java.lang.String | 123
      """;

  private record ReferenceCost(Map<String, ReferenceFigure> byKind) {}

  private static final String DESCRIBE_KIND = "describe";
  private static final String EVIDENCE_KIND = "evidence";

  private static final Map<String, ReferenceCost> REFERENCE_COST =
      referenceCost(REFERENCE_COST_TABLE);

  private static final Map<String, Long> REFERENCE_FRAMES =
      Map.of(
          "describe (es-t CsvFormatReaderTests)", 109_606L,
          "page 500 rows (es-d StreamInput IN)", 140_045L,
          "page 2000 rows (es-d StreamInput IN)", 567_591L,
          "evidence 50 rows (sf-t MessageBrokerBeanDefinitionParser)", 26_572L);

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

  private static final String ENDS =
      "SELECT id, element_id, kind, is_external FROM code_element"
          + " WHERE analysis_pk = ? AND kind IN ('PACKAGE', 'TYPE')";

  private static final String TYPE_IN =
      """
      SELECT d.target_pk, count(DISTINCT d.source_pk) FROM dependency d
       WHERE d.analysis_pk = ? AND d.source_pk <> d.target_pk
       GROUP BY d.target_pk""";

  private static final String TYPE_OUT =
      """
      SELECT d.source_pk,
             count(DISTINCT CASE WHEN t.is_external = 0 THEN d.target_pk END),
             count(DISTINCT CASE WHEN t.is_external = 1 THEN d.target_pk END)
        FROM dependency d JOIN code_element t ON t.id = d.target_pk
       WHERE d.analysis_pk = ? AND d.source_pk <> d.target_pk
       GROUP BY d.source_pk""";

  private static final String PACKAGE_IN =
      "SELECT target_pk, count(*) FROM package_edge WHERE analysis_pk = ? GROUP BY target_pk";

  private static final String PACKAGE_OUT =
      "SELECT source_pk, count(*) FROM package_edge WHERE analysis_pk = ? GROUP BY source_pk";

  private static final String METHODS_PER_TYPE =
      "SELECT parent_pk, count(*) FROM code_element WHERE analysis_pk = ? AND kind = 'METHOD'"
          + " GROUP BY parent_pk";

  private static final String EVIDENCE_PER_PAIR =
      """
      SELECT d.source_pk, d.target_pk, count(*) FROM dependency d
        JOIN dependency_evidence v ON v.dependency_pk = d.id
       WHERE d.analysis_pk = ? AND d.source_pk <> d.target_pk
       GROUP BY d.source_pk, d.target_pk""";

  private static final String METHODS_OF =
      """
      SELECT m.element_id, m.start_line, cm.cc_sum FROM code_element t
        JOIN code_element m ON m.parent_pk = t.id AND m.kind = 'METHOD'
        LEFT JOIN code_metric cm ON cm.element_pk = m.id
       WHERE t.analysis_pk = ? AND t.element_id = ?""";

  private static final String TYPE_IN_RANKING =
      """
      SELECT o.element_id, o.is_external, sum(d.weight) FROM code_element a
        JOIN dependency d ON d.target_pk = a.id AND d.source_pk <> a.id
        JOIN code_element o ON o.id = d.source_pk
       WHERE a.analysis_pk = ? AND a.element_id = ?
       GROUP BY d.source_pk""";

  private static final String TYPE_OUT_RANKING =
      """
      SELECT o.element_id, o.is_external, sum(d.weight) FROM code_element a
        JOIN dependency d ON d.source_pk = a.id AND d.target_pk <> a.id
        JOIN code_element o ON o.id = d.target_pk
       WHERE a.analysis_pk = ? AND a.element_id = ?
       GROUP BY d.target_pk""";

  private static final String PACKAGE_IN_RANKING =
      """
      SELECT o.element_id, o.is_external, p.type_pair_count FROM code_element a
        JOIN package_edge p ON p.target_pk = a.id
        JOIN code_element o ON o.id = p.source_pk
       WHERE a.analysis_pk = ? AND a.element_id = ?""";

  private static final String PAIR_EVIDENCE =
      """
      SELECT d.type, f.relative_path, v.line, fm.element_id, tm.element_id, v.resolution
        FROM code_element s
        JOIN code_element t ON t.analysis_pk = s.analysis_pk AND t.element_id = ?3
        JOIN dependency d
          ON d.analysis_pk = s.analysis_pk AND d.source_pk = s.id AND d.target_pk = t.id
        JOIN dependency_evidence v ON v.dependency_pk = d.id
        LEFT JOIN source_file f ON f.id = v.file_pk
        LEFT JOIN code_element fm ON fm.id = v.from_method_pk
        LEFT JOIN code_element tm ON tm.id = v.to_method_pk
       WHERE s.analysis_pk = ?1 AND s.element_id = ?2""";

  @Test
  void asTresLeiturasDoInspetorPeloDespachanteCabemNoOrcamentoEMantemOPreRegistro()
      throws Exception {
    HermeticSystemReader.install();
    final double loadAtStart = loadAverage();
    final Instant startedAt = Instant.now();
    final long wallStart = System.nanoTime();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final Environment environment = environment();
    final String baseCommit = headOf(strataRoot).substring(0, 7);
    final JsonNode graphReads = JSON.readTree(strataRoot.resolve(GRAPH_READS).toFile());
    final Path corpus = corpusDirectory();
    final Path work = strataRoot.resolve(WORK);
    final List<String> measured = repositoriesToMeasure();
    final Path report =
        measured.equals(REPOSITORIES)
            ? strataRoot.resolve(REPORT)
            : work.resolveSibling("element-reads.restricted.json");
    deleteTree(work);
    Files.createDirectories(work);

    final List<RepositoryResult> repositories = new ArrayList<>();
    for (final String name : measured) {
      final Path repository = corpus.resolve(name).toRealPath();
      final List<SelectionResult> selections = new ArrayList<>();
      for (final Selection selection : Selection.values()) {
        selections.add(measureSelection(name, repository, selection, work, strataRoot));
        final List<RepositoryResult> partial = new ArrayList<>(repositories);
        partial.add(new RepositoryResult(name, headOf(repository), List.copyOf(selections)));
        write(
            report,
            new Report(
                SCHEMA,
                startedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
                baseCommit,
                MEASURE,
                (System.nanoTime() - wallStart) / NANOS_PER_MILLI,
                settings(loadAtStart, null),
                environment,
                List.of(),
                partial));
      }
      repositories.add(new RepositoryResult(name, headOf(repository), List.copyOf(selections)));
    }

    final List<Expectation> expectations = expectations(repositories, graphReads);
    write(
        report,
        new Report(
            SCHEMA,
            startedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            baseCommit,
            MEASURE,
            (System.nanoTime() - wallStart) / NANOS_PER_MILLI,
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
      String name, Path repository, Selection selection, Path work, Path strataRoot)
      throws Exception {
    final Measured first = analyze(name, repository, selection, 1, work, strataRoot, null);
    final Measured second =
        analyze(name, repository, selection, RUNS, work, strataRoot, first.census().targets());
    final Determinism determinism = determinism(first, second, strataRoot);
    if (determinism.sweepTranscriptsIdentical()) {
      Files.delete(strataRoot.resolve(second.run().sweep().transcript().file()));
    }
    if (determinism.worstTranscriptsIdentical()) {
      Files.delete(strataRoot.resolve(second.run().worstTranscript().file()));
    }
    return new SelectionResult(
        selection.name(),
        selection.payload(),
        List.of(first.run(), second.run()),
        first.census().readBack(),
        first.census().view(),
        Objects.requireNonNull(first.worst(), "worst"),
        first.latency(),
        first.timedPhase(),
        determinism);
  }

  private Measured analyze(
      String name,
      Path repository,
      Selection selection,
      int run,
      Path work,
      Path strataRoot,
      Targets targets)
      throws Exception {
    final boolean first = targets == null;
    final String runLabel = name + "-" + selection.name() + "-run" + run;
    final Path data = work.resolve(runLabel);
    final Path sweepFile = work.resolve(runLabel + "-sweep.jsonl");
    final Path worstFile = work.resolve(runLabel + "-worst.jsonl");
    final RecordingFrameSink sink = new RecordingFrameSink();
    final RegistrySettings settings =
        RegistrySettings.production(PersistenceRoot.prepare(data), sink, ANALYZER_VERSION);
    System.gc();
    final double loadStart = loadAverage();
    final Instant analysisStartedAt = Instant.now();
    final AnalysisRegistry registry = new AnalysisRegistry(settings);
    final AnalysisId id;
    final Path database;
    final Census census;
    final SweepRun sweep;
    final WorstReads worstReads;
    WorstResult worst = null;
    List<Latency> latency = List.of();
    TimedPhase timedPhase = null;
    final Instant readsStartedAt;
    try {
      id = RunFixtures.launch(registry, repository, selection.configuration());
      sink.awaitTerminal(id, RUN_TIMEOUT);
      RunFixtures.runThread(sink, id).join(RUN_TIMEOUT.toMillis());
      final ReadableAnalysis readable =
          registry
              .readable(id)
              .orElseThrow(() -> new AssertionError("a análise " + id + " não ficou legível"));
      database = readable.connections().databaseFile();
      readsStartedAt = Instant.now();
      final long pk = readable.analysisPk();
      census = readable.connections().read(c -> census(c, pk));
      final Targets asked = first ? census.targets() : targets;
      final SweepRun swept;
      try (OutputStream transcript =
          new BufferedOutputStream(Files.newOutputStream(sweepFile), 1 << 20)) {
        swept = sweep(new Wire(registry, transcript), id.value(), census);
      }
      sweep = swept.withTranscript(transcript(sweepFile, swept.requests(), strataRoot));
      try (OutputStream transcript =
          new BufferedOutputStream(Files.newOutputStream(worstFile), 1 << 20)) {
        worstReads = worstReads(new Wire(registry, transcript), id.value(), asked);
      }
      if (first) {
        final WorstOracle oracle = readable.connections().read(c -> worstOracle(c, pk, asked));
        worst = WorstResult.of(worstReads, oracle);
        final double timedLoadStart = loadAverage();
        final Instant timedStartedAt = Instant.now();
        System.gc();
        final GcTotals gcBefore = GcTotals.now();
        latency =
            timed(
                new Wire(registry, null),
                readable.connections(),
                id.value(),
                worstReads,
                REFERENCE_COST.get(key(name, selection)));
        final GcTotals gcAfter = GcTotals.now();
        timedPhase =
            new TimedPhase(
                gcAfter.collections() - gcBefore.collections(),
                gcAfter.millis() - gcBefore.millis(),
                new Load(timedLoadStart, loadAverage()),
                timedStartedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
      }
    } finally {
      registry.close();
    }
    final Instant readsEndedAt = Instant.now();
    final long cacheBytes = Files.size(database);
    final double loadEnd = loadAverage();
    final List<Frame> frames = sink.of(id);
    if (!first) {
      deleteTree(data);
    }
    final RunResult result =
        RunResult.of(
            run,
            id,
            frames,
            cacheBytes,
            new Load(loadStart, loadEnd),
            new Timestamps(
                analysisStartedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
                readsStartedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
                readsEndedAt.truncatedTo(ChronoUnit.MILLIS).toString()),
            sweep,
            transcript(worstFile, worstReads.frames(), strataRoot),
            census,
            first ? strataRoot.relativize(database).toString() : null);
    return new Measured(result, census, worst, latency, timedPhase);
  }

  private static final class Wire {

    private final Dispatcher dispatcher;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
    private final JsonLines.Writer writer;
    private final OutputStream transcript;
    private long requests;
    private long largestFrame;

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
      largestFrame = Math.max(largestFrame, line.length - 1L);
      return new Call(nanos, Arrays.copyOf(line, line.length - 1));
    }

    long requests() {
      return requests;
    }

    long largestFrame() {
      return largestFrame;
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

  private record Request(String name, String method, ObjectNode payload, String kind) {}

  private record Call(long nanos, byte[] frame) {

    JsonNode json() throws IOException {
      return JSON.readTree(frame);
    }

    JsonNode response() throws IOException {
      final JsonNode response = json();
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

    long payloadBytes() {
      final int infixAt = ID_PREFIX.length + REQUEST_ID_CHARS;
      final int payloadAt = infixAt + OK_INFIX.length;
      final int suffixAt = frame.length - OK_SUFFIX.length;
      final boolean shaped =
          suffixAt > payloadAt
              && Arrays.equals(frame, infixAt, payloadAt, OK_INFIX, 0, OK_INFIX.length)
              && Arrays.equals(frame, suffixAt, frame.length, OK_SUFFIX, 0, OK_SUFFIX.length);
      return shaped ? suffixAt - payloadAt : -1;
    }
  }

  private static Request describe(String analysisId, String elementId) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    payload.put("elementId", elementId);
    return new Request("element.describe " + elementId, "element.describe", payload, DESCRIBE_KIND);
  }

  private static Request dependencies(
      String analysisId, String elementId, String direction, String cursor, int limit, int page) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    if (cursor != null) {
      payload.put("cursor", cursor);
    }
    payload.put("direction", direction);
    payload.put("elementId", elementId);
    payload.put("limit", limit);
    final String kind = (elementId.startsWith("pkg:") ? "package " : "") + direction + " " + limit;
    return new Request(
        "element.dependencies " + kind + " page " + page + " " + elementId,
        "element.dependencies",
        payload,
        page == 1 ? kind : null);
  }

  private static Request evidence(String analysisId, Pair pair) {
    final ObjectNode payload = JSON.createObjectNode();
    payload.put("analysisId", analysisId);
    payload.put("source", pair.source());
    payload.put("target", pair.target());
    return new Request(
        "element.dependencyEvidence " + pair.source() + " -> " + pair.target(),
        "element.dependencyEvidence",
        payload,
        EVIDENCE_KIND);
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
    return new Request(name, "graph.slice", payload, null);
  }

  private static Census census(Connection c, long pk) throws SQLException {
    final ReadBack readBack = readBack(c, pk);
    final Map<Long, End> ends = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(ENDS)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ends.put(
              rows.getLong(1), new End(rows.getString(2), rows.getString(3), rows.getInt(4) == 1));
        }
      }
    }
    final Map<Long, Long> typeIn = counts(c, TYPE_IN, pk);
    final Map<Long, long[]> typeOut = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(TYPE_OUT)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          typeOut.put(rows.getLong(1), new long[] {rows.getLong(2), rows.getLong(3)});
        }
      }
    }
    final Map<Long, Long> packageIn = counts(c, PACKAGE_IN, pk);
    final Map<Long, Long> packageOut = counts(c, PACKAGE_OUT, pk);
    final Map<Long, Long> methods = counts(c, METHODS_PER_TYPE, pk);
    final List<OracleElement> elements = new ArrayList<>();
    for (final Map.Entry<Long, End> entry : ends.entrySet()) {
      final End end = entry.getValue();
      if (end.external()) {
        continue;
      }
      final long elementPk = entry.getKey();
      if (PACKAGE.equals(end.kind())) {
        elements.add(
            new OracleElement(
                end.id(),
                PACKAGE,
                packageIn.getOrDefault(elementPk, 0L),
                packageOut.getOrDefault(elementPk, 0L),
                null,
                0L));
      } else {
        final long[] out = typeOut.getOrDefault(elementPk, new long[] {0, 0});
        elements.add(
            new OracleElement(
                end.id(),
                TYPE,
                typeIn.getOrDefault(elementPk, 0L),
                out[0],
                out[1],
                methods.getOrDefault(elementPk, 0L)));
      }
    }
    elements.sort(Comparator.comparing(OracleElement::id, ElementReadsSpikeIT::compareCodePoints));
    final List<PairCount> pairs = topEvidencePairs(c, pk, ends);
    final CensusView view =
        new CensusView(
            elements.stream().filter(e -> PACKAGE.equals(e.kind())).count(),
            elements.stream().filter(e -> TYPE.equals(e.kind())).count(),
            worst(elements, TYPE, OracleElement::methods),
            worst(elements, TYPE, OracleElement::in),
            worstOut(elements),
            worst(elements, PACKAGE, OracleElement::in),
            pairs);
    return new Census(readBack, List.copyOf(elements), view);
  }

  private static Worst worst(
      List<OracleElement> elements, String kind, ToLongFunction<OracleElement> measure) {
    OracleElement best = null;
    for (final OracleElement element : elements) {
      if (kind.equals(element.kind())
          && (best == null || measure.applyAsLong(element) > measure.applyAsLong(best))) {
        best = element;
      }
    }
    Objects.requireNonNull(best, "nenhum " + kind);
    return new Worst(best.id(), measure.applyAsLong(best));
  }

  private static WorstOut worstOut(List<OracleElement> elements) {
    final Worst total = worst(elements, TYPE, e -> e.out() + Objects.requireNonNull(e.external()));
    final OracleElement element =
        elements.stream().filter(e -> e.id().equals(total.elementId())).findFirst().orElseThrow();
    return new WorstOut(element.id(), element.out(), Objects.requireNonNull(element.external()));
  }

  private static List<PairCount> topEvidencePairs(Connection c, long pk, Map<Long, End> ends)
      throws SQLException {
    final Comparator<PairCount> order =
        Comparator.comparingLong(PairCount::storedRows)
            .reversed()
            .thenComparing(PairCount::source, ElementReadsSpikeIT::compareCodePoints)
            .thenComparing(PairCount::target, ElementReadsSpikeIT::compareCodePoints);
    final List<PairCount> top = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(EVIDENCE_PER_PAIR)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final long stored = rows.getLong(3);
          if (top.size() == EVIDENCE_PAIRS && stored < top.getLast().storedRows()) {
            continue;
          }
          top.add(
              new PairCount(
                  ends.get(rows.getLong(1)).id(), ends.get(rows.getLong(2)).id(), stored));
          top.sort(order);
          if (top.size() > EVIDENCE_PAIRS) {
            top.removeLast();
          }
        }
      }
    }
    return List.copyOf(top);
  }

  private static Map<Long, Long> counts(Connection c, String sql, long pk) throws SQLException {
    final Map<Long, Long> counts = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, pk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          counts.put(rows.getLong(1), rows.getLong(2));
        }
      }
    }
    return counts;
  }

  private static ReadBack readBack(Connection c, long pk) throws SQLException {
    return new ReadBack(elements(c, pk), dependencies(c, pk), metricRows(c, pk));
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
        internal.getOrDefault(PACKAGE, 0L),
        internal.getOrDefault(TYPE, 0L),
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
    for (final String kind : List.of(PACKAGE, TYPE, "METHOD", "FILE", "external")) {
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

  private static SweepRun sweep(Wire wire, String analysisId, Census census)
      throws IOException, NoSuchAlgorithmException {
    final long started = System.nanoTime();
    final MessageDigest totalsDigest = MessageDigest.getInstance("SHA-256");
    final Map<String, Long> describeErrors = new TreeMap<>();
    final Map<String, Long> pageErrors = new TreeMap<>();
    final Tally fans = new Tally();
    final Tally sql = new Tally();
    final Tally nullIffPackage = new Tally();
    final Tally bothDirections = new Tally();
    final Tally cursors = new Tally();
    final Tally methodTotals = new Tally();
    final Largest largestDescribe = new Largest();
    final Largest largestPage = new Largest();
    for (final OracleElement element : census.elements()) {
      final Call describeCall = wire.call(describe(analysisId, element.id()));
      final JsonNode describe = describeCall.json();
      largestDescribe.offer(element.id(), describeCall.frame().length);
      final Call inCall =
          wire.call(dependencies(analysisId, element.id(), IN, null, SWEEP_LIMIT, 1));
      final Call outCall =
          wire.call(dependencies(analysisId, element.id(), OUT, null, SWEEP_LIMIT, 1));
      largestPage.offer(element.id() + " IN", inCall.frame().length);
      largestPage.offer(element.id() + " OUT", outCall.frame().length);
      final JsonNode in = inCall.json();
      final JsonNode out = outCall.json();
      final boolean describeOk = countError(describe, describeErrors);
      final boolean pagesOk = countError(in, pageErrors) & countError(out, pageErrors);
      final JsonNode detail = describe.path("payload");
      final JsonNode inTotals = in.path("payload").path("totals");
      final JsonNode outTotals = out.path("payload").path("totals");
      totalsDigest.update(
          (element.id()
                  + "\t"
                  + inTotals
                  + "\t"
                  + outTotals
                  + "\t"
                  + detail.path("graph").path("fanIn")
                  + "\t"
                  + detail.path("graph").path("fanOut")
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      if (describeOk) {
        checkMethods(element, detail, methodTotals);
      }
      if (!pagesOk) {
        continue;
      }
      if (describeOk) {
        final long fanIn = detail.path("graph").path("fanIn").asLong(-1);
        final long fanOut = detail.path("graph").path("fanOut").asLong(-1);
        if (inTotals.path("in").asLong(-2) != fanIn
            || inTotals.path("out").asLong(-2) != fanOut
            || outTotals.path("in").asLong(-2) != fanIn
            || outTotals.path("out").asLong(-2) != fanOut) {
          fans.add(element.id());
        }
      }
      if (!matchesSql(inTotals, element) || !matchesSql(outTotals, element)) {
        sql.add(element.id());
      }
      final boolean isPackage = PACKAGE.equals(element.kind());
      if (inTotals.path("outExternal").isNull() != isPackage
          || outTotals.path("outExternal").isNull() != isPackage) {
        nullIffPackage.add(element.id());
      }
      if (!inTotals.equals(outTotals)) {
        bothDirections.add(element.id());
      }
      final long outRows = element.out() + (element.external() == null ? 0 : element.external());
      if (!pageShapeOk(in.path("payload"), element.in())
          || !pageShapeOk(out.path("payload"), outRows)) {
        cursors.add(element.id());
      }
    }
    final long millis = (System.nanoTime() - started) / NANOS_PER_MILLI;
    return new SweepRun(
        census.elements().size(),
        census.view().packages(),
        census.view().types(),
        wire.requests(),
        millis,
        describeErrors,
        pageErrors,
        new SweepChecks(
            fans.view(),
            sql.view(),
            nullIffPackage.view(),
            bothDirections.view(),
            cursors.view(),
            methodTotals.view()),
        largestDescribe.view(),
        largestPage.view(),
        wire.largestFrame(),
        null,
        HEX.formatHex(totalsDigest.digest()));
  }

  private static boolean countError(JsonNode response, Map<String, Long> errors) {
    if (response.path("ok").asBoolean(false)) {
      return true;
    }
    errors.merge(response.path("error").path("code").asText("?"), 1L, Long::sum);
    return false;
  }

  private static boolean matchesSql(JsonNode totals, OracleElement element) {
    final boolean external =
        element.external() == null
            ? totals.path("outExternal").isNull()
            : totals.path("outExternal").isIntegralNumber()
                && totals.path("outExternal").asLong() == element.external();
    return totals.path("in").asLong(-1) == element.in()
        && totals.path("out").asLong(-1) == element.out()
        && external;
  }

  private static boolean pageShapeOk(JsonNode page, long rows) {
    final JsonNode next = page.path("nextCursor");
    final boolean cursorOk =
        rows > SWEEP_LIMIT
            ? next.isTextual() && next.asText().length() == DEPENDENCY_CURSOR_CHARS
            : next.isNull();
    return page.path("items").size() == Math.min(rows, SWEEP_LIMIT) && cursorOk;
  }

  private static void checkMethods(OracleElement element, JsonNode detail, Tally violations) {
    final long total = detail.path("methodTotal").asLong(-1);
    final int listed = detail.path("methods").size();
    final boolean ok =
        PACKAGE.equals(element.kind())
            ? total == 0 && listed == 0
            : total == element.methods()
                && listed == Math.min(total, AnalysisConstants.ELEMENT_METHODS_MAX);
    if (!ok) {
      violations.add(element.id());
    }
  }

  private static WorstReads worstReads(Wire wire, String analysisId, Targets targets)
      throws IOException {
    final Call level1 = wire.call(slice(analysisId, PACKAGE, null));
    final Call typeSlice = wire.call(slice(analysisId, TYPE, targets.inPackage()));
    final Call describe = wire.call(describe(analysisId, targets.methodsType()));
    final List<Chain> chains = new ArrayList<>();
    for (final String[] chain :
        List.of(
            new String[] {"type IN", targets.inType(), IN},
            new String[] {"type OUT", targets.outType(), OUT},
            new String[] {"package IN", targets.inPackage(), IN})) {
      for (final int limit : CHAIN_LIMITS) {
        chains.add(walk(wire, analysisId, chain[0] + " " + limit, chain[1], chain[2], limit));
      }
    }
    final List<Call> evidence = new ArrayList<>();
    for (final Pair pair : targets.evidencePairs()) {
      evidence.add(wire.call(evidence(analysisId, pair)));
    }
    return new WorstReads(
        analysisId,
        targets,
        level1,
        typeSlice,
        describe,
        List.copyOf(chains),
        List.copyOf(evidence),
        wire.requests(),
        wire.largestFrame());
  }

  private static Chain walk(
      Wire wire, String analysisId, String name, String elementId, String direction, int limit)
      throws IOException {
    final List<ChainPage> pages = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    String cursor = null;
    String lastCursor = null;
    do {
      if (pages.size() >= MAX_PAGES) {
        throw new AssertionError(name + " " + elementId + ": mais de " + MAX_PAGES + " páginas");
      }
      final Call call =
          wire.call(
              dependencies(analysisId, elementId, direction, cursor, limit, pages.size() + 1));
      final JsonNode page = call.response();
      for (final JsonNode item : page.path("items")) {
        rows.add(
            new Row(
                item.path("elementId").asText(),
                item.path("weight").asLong(),
                item.path("isExternal").asBoolean()));
      }
      lastCursor = cursor;
      final JsonNode next = page.path("nextCursor");
      cursor = next.isNull() ? null : next.asText();
      pages.add(
          new ChainPage(
              page.path("items").size(),
              call.frame().length,
              call.payloadBytes(),
              cursor == null ? 0 : cursor.length(),
              page.path("totals")));
    } while (cursor != null);
    return new Chain(
        name, elementId, direction, limit, List.copyOf(pages), List.copyOf(rows), lastCursor);
  }

  private static WorstOracle worstOracle(Connection c, long pk, Targets targets)
      throws SQLException {
    final List<MethodRow> methods = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(METHODS_OF)) {
      statement.setLong(1, pk);
      statement.setString(2, targets.methodsType());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          methods.add(
              new MethodRow(rows.getString(1), nullableInt(rows, 2), nullableLong(rows, 3)));
        }
      }
    }
    methods.sort(
        Comparator.comparing(
                MethodRow::start, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(MethodRow::elementId, ElementReadsSpikeIT::compareCodePoints));
    final Map<String, List<Row>> rankings = new LinkedHashMap<>();
    rankings.put("type IN", ranking(c, pk, TYPE_IN_RANKING, targets.inType()));
    rankings.put("type OUT", ranking(c, pk, TYPE_OUT_RANKING, targets.outType()));
    rankings.put("package IN", ranking(c, pk, PACKAGE_IN_RANKING, targets.inPackage()));
    final List<List<EvidenceKey>> stored = new ArrayList<>();
    for (final Pair pair : targets.evidencePairs()) {
      final List<EvidenceKey> keys = new ArrayList<>();
      try (PreparedStatement statement = c.prepareStatement(PAIR_EVIDENCE)) {
        statement.setLong(1, pk);
        statement.setString(2, pair.source());
        statement.setString(3, pair.target());
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            keys.add(
                new EvidenceKey(
                    rows.getString(1),
                    rows.getString(2),
                    nullableInt(rows, 3),
                    rows.getString(4),
                    rows.getString(5),
                    rows.getString(6)));
          }
        }
      }
      stored.add(List.copyOf(keys));
    }
    return new WorstOracle(List.copyOf(methods), rankings, List.copyOf(stored));
  }

  private static Integer nullableInt(ResultSet rows, int column) throws SQLException {
    final int value = rows.getInt(column);
    return rows.wasNull() ? null : value;
  }

  private static Long nullableLong(ResultSet rows, int column) throws SQLException {
    final long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private static List<Row> ranking(Connection c, long pk, String sql, String elementId)
      throws SQLException {
    final List<Row> rows = new ArrayList<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, pk);
      statement.setString(2, elementId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(new Row(result.getString(1), result.getLong(3), result.getInt(2) == 1));
        }
      }
    }
    rows.sort(
        Comparator.comparing(Row::external)
            .thenComparing(Comparator.comparingLong(Row::weight).reversed())
            .thenComparing(Row::elementId, ElementReadsSpikeIT::compareCodePoints));
    return List.copyOf(rows);
  }

  private static List<EvidenceKey> selectEvidence(List<EvidenceKey> stored, int limit) {
    final Map<Integer, List<EvidenceKey>> byType = new TreeMap<>();
    for (final EvidenceKey key : stored) {
      byType
          .computeIfAbsent(DependencyType.valueOf(key.type()).ordinal(), t -> new ArrayList<>())
          .add(key);
    }
    final Comparator<String> nullFirst =
        Comparator.nullsFirst(ElementReadsSpikeIT::compareCodePoints);
    final Comparator<EvidenceKey> content =
        Comparator.comparing(
                EvidenceKey::line, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(EvidenceKey::fromMethod, nullFirst)
            .thenComparing(EvidenceKey::toMethod, nullFirst)
            .thenComparingInt(key -> ResolutionQuality.valueOf(key.resolution()).ordinal())
            .thenComparing(EvidenceKey::filePath, nullFirst);
    byType.values().forEach(keys -> keys.sort(content));
    final Map<Integer, Integer> taken = new TreeMap<>();
    byType.keySet().forEach(type -> taken.put(type, 0));
    int total = 0;
    boolean moved = true;
    while (total < limit && moved) {
      moved = false;
      for (final Map.Entry<Integer, List<EvidenceKey>> entry : byType.entrySet()) {
        final int count = taken.get(entry.getKey());
        if (total < limit && count < entry.getValue().size()) {
          taken.put(entry.getKey(), count + 1);
          total++;
          moved = true;
        }
      }
    }
    final List<EvidenceKey> selected = new ArrayList<>();
    byType.forEach((type, keys) -> selected.addAll(keys.subList(0, taken.get(type))));
    return List.copyOf(selected);
  }

  private static List<Latency> timed(
      Wire wire,
      SqliteConnections connections,
      String analysisId,
      WorstReads worst,
      ReferenceCost reference)
      throws IOException {
    final List<Request> requests = new ArrayList<>();
    requests.add(describe(analysisId, worst.targets().methodsType()));
    for (final Chain chain : worst.chains()) {
      requests.add(
          dependencies(analysisId, chain.elementId(), chain.direction(), null, chain.limit(), 1));
      if (chain.pages().size() > 1) {
        requests.add(
            dependencies(
                analysisId,
                chain.elementId(),
                chain.direction(),
                chain.lastCursor(),
                chain.limit(),
                chain.pages().size()));
      }
    }
    for (final Pair pair : worst.targets().evidencePairs()) {
      requests.add(evidence(analysisId, pair));
    }
    final List<Latency> latency = new ArrayList<>();
    for (final Request request : requests) {
      latency.add(timed(wire, connections, request, reference));
    }
    return List.copyOf(latency);
  }

  private static Latency timed(
      Wire wire, SqliteConnections connections, Request request, ReferenceCost reference)
      throws IOException {
    discardIdleReaders(connections);
    final Call cold = wire.call(request);
    cold.response();
    final long[] warm = new long[WARM_RUNS];
    boolean identical = true;
    for (int run = 0; run < WARM_RUNS; run++) {
      final Call call = wire.call(request);
      warm[run] = call.nanos() / NANOS_PER_MICRO;
      identical &= call.samePayload(cold);
    }
    final long[] sorted = warm.clone();
    Arrays.sort(sorted);
    final long median = sorted[WARM_RUNS / 2];
    final ReferenceFigure figure =
        reference == null || request.kind() == null ? null : reference.byKind().get(request.kind());
    return new Latency(
        request.name(),
        request.method(),
        request.kind(),
        cold.nanos() / NANOS_PER_MICRO,
        median,
        sorted[WARM_RUNS - 1],
        Arrays.stream(warm).boxed().toList(),
        cold.frame().length,
        cold.payloadBytes(),
        identical,
        figure == null
            ? null
            : new ReferenceRatio(
                figure.elementId(),
                figure.warmMedianMicros(),
                Math.round(1_000.0 * median / figure.warmMedianMicros()) / 1_000.0));
  }

  private static void discardIdleReaders(SqliteConnections connections) {
    for (int reader = 0; reader < AnalysisConstants.SQLITE_READER_CONNECTIONS; reader++) {
      try {
        connections.read(
            c -> {
              throw new DiscardedReader();
            });
      } catch (DiscardedReader expected) {
      } catch (SQLException impossible) {
        throw new IllegalStateException("a leitura que lança falhou de outro jeito", impossible);
      }
    }
  }

  private static final class DiscardedReader extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DiscardedReader() {
      super("descarte o leitor emprestado", null, false, false);
    }
  }

  private static Determinism determinism(Measured first, Measured second, Path strataRoot)
      throws IOException {
    final Transcript sweepOne = first.run().sweep().transcript();
    final Transcript sweepTwo = second.run().sweep().transcript();
    final Transcript worstOne = first.run().worstTranscript();
    final Transcript worstTwo = second.run().worstTranscript();
    final boolean sweepIdentical =
        sweepOne.sha256().equals(sweepTwo.sha256()) && sweepOne.bytes() == sweepTwo.bytes();
    final boolean worstIdentical =
        worstOne.sha256().equals(worstTwo.sha256()) && worstOne.bytes() == worstTwo.bytes();
    return new Determinism(
        worstIdentical,
        worstOne.sha256(),
        worstTwo.sha256(),
        worstIdentical
            ? -1
            : firstDifferentLine(
                strataRoot.resolve(worstOne.file()), strataRoot.resolve(worstTwo.file())),
        sweepIdentical,
        sweepOne.sha256(),
        sweepTwo.sha256(),
        sweepIdentical
            ? -1
            : firstDifferentLine(
                strataRoot.resolve(sweepOne.file()), strataRoot.resolve(sweepTwo.file())),
        first.run().sweep().totalsSha256().equals(second.run().sweep().totalsSha256()),
        first.latency().stream().allMatch(Latency::identicalResponses),
        first.census().view().equals(second.census().view()));
  }

  private static long firstDifferentLine(Path left, Path right) throws IOException {
    try (BufferedReader a = Files.newBufferedReader(left, StandardCharsets.UTF_8);
        BufferedReader b = Files.newBufferedReader(right, StandardCharsets.UTF_8)) {
      long line = 1;
      while (true) {
        final String x = a.readLine();
        final String y = b.readLine();
        if (x == null || !x.equals(y)) {
          return line;
        }
        line++;
      }
    }
  }

  private static List<Expectation> expectations(
      List<RepositoryResult> repositories, JsonNode graphReads) {
    final Checks checks = new Checks();
    for (final RepositoryResult repository : repositories) {
      final JsonNode baseline = find(graphReads.path("repositories"), "name", repository.name());
      checks.equal(
          "R1/" + repository.name() + "/commit",
          "the clone is at graph-reads.json's commit",
          baseline.path("commitId").asText(),
          repository.commitId());
      for (final SelectionResult result : repository.selections()) {
        final Selection selection = Selection.valueOf(result.selection());
        final String at = repository.name() + "/" + selection.name();
        final JsonNode reads = find(baseline.path("selections"), "selection", selection.name());
        nothingMoved(checks, at, result, reads);
        sweepChecks(checks, at, result);
        worstChecks(checks, at, repository.name(), selection, result);
        latencyChecks(checks, at, result);
        frameChecks(checks, at, result);
        determinismChecks(checks, at, result);
        methodChecks(checks, at, result);
      }
    }
    return checks.list();
  }

  private static void nothingMoved(
      Checks checks, String at, SelectionResult result, JsonNode reads) {
    final JsonNode readBack = reads.path("readBack");
    final JsonNode counters = reads.path("runs").get(0).path("structuralGraph").path("counters");
    for (final RunResult run : result.runs()) {
      final String where = "R1/" + at + "/run" + run.run();
      checks.equal(
          where + "/elements",
          "elements equal graph-reads.json",
          readBack.path("elements"),
          tree(run.readBack().elements()));
      checks.equal(
          where + "/dependencies",
          "dependency, evidence and module rows equal graph-reads.json",
          readBack.path("dependencies"),
          tree(run.readBack().dependencies()));
      checks.equal(
          where + "/metricRows",
          "code_metric rows equal graph-reads.json",
          readBack.path("metricRows"),
          tree(run.readBack().metricRows()));
      checks.equal(
          where + "/STRUCTURAL_GRAPH.counters",
          "the thirteen STRUCTURAL_GRAPH counters equal graph-reads.json",
          counters,
          run.structuralGraph().counters());
      checks.equal(
          where + "/status",
          "STRUCTURAL_GRAPH COMPLETED, the analysis analysis.completed",
          "COMPLETED " + ANALYSIS_COMPLETED,
          run.structuralGraph().status() + " " + run.terminalEvent());
    }
  }

  private static void sweepChecks(Checks checks, String at, SelectionResult result) {
    final SweepRun sweep = result.runs().get(0).sweep();
    final String where = "R2/" + at;
    final long elements =
        result.readBack().elements().packages() + result.readBack().elements().types();
    checks.equal(
        where + "/elements",
        "the sweep covers every internal PACKAGE and TYPE row",
        elements,
        sweep.elements());
    checks.equal(
        where + "/describe",
        "element.describe answers every internal PACKAGE and TYPE (no INTERNAL_UNEXPECTED, no"
            + " ELEMENT_NOT_FOUND)",
        "{}",
        String.valueOf(sweep.describeErrors()));
    checks.equal(
        where + "/pages",
        "element.dependencies IN and OUT at limit 1 answer every internal PACKAGE and TYPE",
        "{}",
        String.valueOf(sweep.pageErrors()));
    final SweepChecks c = sweep.checks();
    violations(
        checks,
        where + "/totalsEqualFans",
        "totals.in == describe graph.fanIn and totals.out == graph.fanOut, both pages",
        c.totalsVsFans());
    violations(
        checks,
        where + "/totalsEqualSql",
        "totals {in, out, outExternal} equal the IT's own SQL counts of distinct non-self ends",
        c.totalsVsSql());
    violations(
        checks,
        where + "/outExternalNullIffPackage",
        "totals.outExternal is null iff the element is a PACKAGE",
        c.outExternalNullIffPackage());
    violations(
        checks,
        where + "/totalsBothDirections",
        "the IN and the OUT page carry the same totals",
        c.totalsBothDirections());
    violations(
        checks,
        where + "/cursorIffRowsRemain",
        "a limit-1 page holds min(1, rows) items and a 24-char nextCursor iff rows remain",
        c.cursorIffRowsRemain());
    violations(
        checks,
        where + "/methodTotals",
        "methodTotal == the type's METHOD rows and methods.length == min(methodTotal,"
            + " ELEMENT_METHODS_MAX); a PACKAGE has [] and 0",
        c.methodTotals());
  }

  private static void violations(Checks checks, String id, String claim, Violations violations) {
    checks.that(
        id,
        claim,
        "0 violations",
        violations.count()
            + " violations"
            + (violations.count() == 0 ? "" : " e.g. " + violations.examples()),
        violations.count() == 0);
  }

  private static void worstChecks(
      Checks checks, String at, String name, Selection selection, SelectionResult result) {
    final CensusView census = result.census();
    final ReferenceCensus reference = REFERENCE_CENSUS.get(key(name, selection));
    final String where = "R3/" + at;
    checks.equal(
        where + "/census.methods",
        "the TYPE with the most METHOD rows equals the reference",
        reference.methods(),
        census.methods());
    checks.equal(
        where + "/census.fanIn",
        "the TYPE with the most distinct internal sources equals the reference",
        reference.fanIn(),
        census.fanIn());
    checks.equal(
        where + "/census.outTotal",
        "the TYPE with the most distinct targets, internal + external, equals the reference",
        reference.outTotal(),
        new Worst(census.out().elementId(), census.out().internal() + census.out().external()));
    checks.equal(
        where + "/census.packageIn",
        "the PACKAGE with the most IN package edges equals the reference",
        reference.packageIn(),
        census.packageIn());
    final WorstResult worst = result.worst();
    checks.equal(
        where + "/describe.methodOrder",
        "the worst describe lists its methods in line order (lines.start ASC, null last, then id)"
            + " with cc == code_metric.cc_sum",
        "order=true cc=true",
        "order=" + worst.describe().orderEqualsSql() + " cc=" + worst.describe().ccEqualsSql());
    for (final ChainCheck chain : worst.chains()) {
      final String c = where + "/" + chain.name().replace(' ', '.');
      checks.that(
          c + "/noGap",
          "the chain reaches every distinct other end the IT's SQL finds, and nothing else",
          "0 missing, 0 extra",
          chain.missing() + " missing, " + chain.extra() + " extra",
          chain.missing() == 0 && chain.extra() == 0);
      checks.equal(c + "/noRepeat", "no other end appears twice in the chain", 0L, chain.repeats());
      checks.equal(
          c + "/sumEqualsTotals",
          "Σ rows over the pages == totals (IN: in; OUT: out + outExternal)",
          chain.expectedRows(),
          chain.rows());
      checks.equal(
          c + "/rank",
          "the chain is the IT's SQL ranking: internal before external, Σ weight DESC, then id"
              + " by code point",
          -1,
          chain.firstRankMismatch());
      checks.equal(
          c + "/paging",
          "every page full but the last, a 24-char nextCursor iff rows remain, the same totals on"
              + " every page",
          true,
          chain.pagingShape());
    }
    for (int index = 0; index < CHAIN_LIMITS.size() * 3; index += CHAIN_LIMITS.size()) {
      final ChainCheck small = worst.chains().get(index);
      final ChainCheck large = worst.chains().get(index + 1);
      checks.equal(
          where + "/" + small.name().replace(' ', '.') + "/agreesWith" + large.limit(),
          "the chains at limit "
              + small.limit()
              + " and "
              + large.limit()
              + " list the same rows in the same order",
          true,
          small.sequenceSha256().equals(large.sequenceSha256()));
    }
    for (int index = 0; index < worst.evidence().size(); index++) {
      final EvidenceCheck evidence = worst.evidence().get(index);
      final String e = where + "/evidence" + (index + 1);
      checks.equal(
          e + "/types",
          "every stored DependencyType of the pair appears",
          evidence.storedTypes(),
          evidence.wireTypes());
      checks.that(
          e + "/rows",
          "rows == min(stored, " + AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX + ")",
          "== "
              + Math.min(evidence.storedRows(), AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX),
          evidence.rows(),
          evidence.rows()
              == Math.min(
                  evidence.storedRows(), AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX));
      checks.equal(
          e + "/selection",
          "the rows follow the selection rule: round-robin across the types in declaration order,"
              + " content order inside a type",
          "order=true multiset=true",
          "order=" + evidence.orderEqualsOracle() + " multiset=" + evidence.multisetEqualsOracle());
    }
  }

  private static void latencyChecks(Checks checks, String at, SelectionResult result) {
    for (final Latency call : result.latency()) {
      checks.that(
          "R4/" + at + "/" + call.request(),
          "warm median of " + WARM_RUNS + " after the cold call <= 65 ms",
          "<= " + WARM_BUDGET_MICROS + " us",
          call.warmMedianMicros() + " us",
          call.warmMedianMicros() <= WARM_BUDGET_MICROS);
    }
  }

  private static void frameChecks(Checks checks, String at, SelectionResult result) {
    final WorstResult worst = result.worst();
    final String where = "R5/" + at;
    checks.that(
        where + "/describe",
        "the worst describe frame <= " + DESCRIBE_FRAME_BUDGET + " B",
        "<= " + DESCRIBE_FRAME_BUDGET,
        worst.describe().frameBytes(),
        worst.describe().frameBytes() <= DESCRIBE_FRAME_BUDGET);
    for (final ChainCheck chain : worst.chains()) {
      final long budget =
          chain.limit() == AnalysisConstants.DEPENDENCY_PAGE_DEFAULT
              ? PAGE_500_FRAME_BUDGET
              : PAGE_2000_FRAME_BUDGET;
      checks.that(
          where + "/" + chain.name().replace(' ', '.'),
          "the largest page frame of the chain at limit " + chain.limit() + " <= " + budget + " B",
          "<= " + budget,
          chain.maxFrameBytes() + " (" + chain.maxPageRows() + " rows)",
          chain.maxFrameBytes() <= budget);
    }
    for (int index = 0; index < worst.evidence().size(); index++) {
      final EvidenceCheck evidence = worst.evidence().get(index);
      checks.that(
          where + "/evidence" + (index + 1),
          "the evidence frame <= " + EVIDENCE_FRAME_BUDGET + " B",
          "<= " + EVIDENCE_FRAME_BUDGET,
          evidence.frameBytes() + " (" + evidence.rows() + " rows)",
          evidence.frameBytes() <= EVIDENCE_FRAME_BUDGET);
    }
    final RunResult first = result.runs().get(0);
    final long largest = Math.max(first.sweep().largestFrameBytes(), worst.largestFrameBytes());
    checks.that(
        where + "/softBudget",
        "every frame of analysis 1 (sweep and worst transcript) fits the 8 MiB soft budget",
        "<= " + SOFT_RESPONSE_BYTES,
        largest,
        largest <= SOFT_RESPONSE_BYTES);
  }

  private static void determinismChecks(Checks checks, String at, SelectionResult result) {
    final Determinism d = result.determinism();
    final String where = "R6/" + at;
    checks.equal(
        where + "/worstTranscript",
        "the second analysis's worst transcript is byte-identical to the first's",
        d.worstSha256Run1(),
        d.worstSha256Run2());
    checks.equal(
        where + "/sweepTranscript",
        "the second analysis's sweep transcript is byte-identical to the first's",
        d.sweepSha256Run1(),
        d.sweepSha256Run2());
    checks.equal(
        where + "/sweepTotals",
        "the limit-1 sweep's totals and fans are the same in both analyses",
        true,
        d.sweepTotalsIdentical());
    checks.equal(
        where + "/repeats",
        "every timed request answered the same payload " + (WARM_RUNS + 1) + " times",
        true,
        d.repeatsIdentical());
    checks.equal(
        where + "/census",
        "the second analysis's SQL census names the same worst elements and pairs",
        true,
        d.censusIdentical());
  }

  private static void methodChecks(Checks checks, String at, SelectionResult result) {
    final WorstResult worst = result.worst();
    final String where = "R7/" + at;
    checks.equal(
        where + "/level1",
        "no method: id among the level-1 slice's nodes and edge ends",
        0L,
        worst.level1().methodIds());
    checks.equal(
        where + "/typeSlice",
        "no method: id in the worst package's TYPE slice",
        0L,
        worst.typeSlice().methodIds());
    final DescribeCheck describe = worst.describe();
    checks.that(
        where + "/describeCap",
        "the worst describe: methodTotal == its METHOD rows, methods.length == min(methodTotal, "
            + AnalysisConstants.ELEMENT_METHODS_MAX
            + ")",
        "methodTotal == "
            + describe.methodRows()
            + ", methods == "
            + Math.min(describe.methodRows(), AnalysisConstants.ELEMENT_METHODS_MAX),
        "methodTotal " + describe.methodTotal() + ", methods " + describe.methods(),
        describe.methodTotal() == describe.methodRows()
            && describe.methods()
                == Math.min(describe.methodRows(), AnalysisConstants.ELEMENT_METHODS_MAX));
  }

  private static JsonNode tree(Object value) {
    try {
      return JSON.readTree(JSON.writeValueAsBytes(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static JsonNode find(JsonNode array, String field, String value) {
    for (final JsonNode element : array) {
      if (value.equals(element.path(field).asText())) {
        return element;
      }
    }
    throw new AssertionError("graph-reads.json não tem " + field + " = " + value);
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

  private static final class Tally {

    private long count;
    private final List<String> examples = new ArrayList<>();

    void add(String elementId) {
      count++;
      if (examples.size() < EXAMPLES) {
        examples.add(elementId);
      }
    }

    Violations view() {
      return new Violations(count, List.copyOf(examples));
    }
  }

  private static final class Largest {

    private String elementId;
    private long frameBytes = -1;

    void offer(String id, long bytes) {
      if (bytes > frameBytes) {
        elementId = id;
        frameBytes = bytes;
      }
    }

    LargestFrame view() {
      return new LargestFrame(elementId, frameBytes);
    }
  }

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

  private static List<String[]> tableRows(String table) {
    return table
        .lines()
        .filter(line -> !line.isBlank())
        .map(line -> Arrays.stream(line.split("\\|")).map(String::strip).toArray(String[]::new))
        .toList();
  }

  private static Map<String, ReferenceCensus> referenceCensus(String table) {
    final Map<String, Map<String, Worst>> bySelection = new HashMap<>();
    for (final String[] row : tableRows(table)) {
      bySelection
          .computeIfAbsent(row[0], selection -> new HashMap<>())
          .put(row[1], new Worst(row[2], Long.parseLong(row[3])));
    }
    final Map<String, ReferenceCensus> census = new HashMap<>();
    bySelection.forEach(
        (selection, worst) ->
            census.put(
                selection,
                new ReferenceCensus(
                    worst.get("methods"),
                    worst.get("fanIn"),
                    worst.get("outTotal"),
                    worst.get("packageIn"))));
    return Map.copyOf(census);
  }

  private static Map<String, ReferenceCost> referenceCost(String table) {
    final Map<String, Map<String, ReferenceFigure>> bySelection = new HashMap<>();
    for (final String[] row : tableRows(table)) {
      bySelection
          .computeIfAbsent(row[0], selection -> new HashMap<>())
          .put(row[1], new ReferenceFigure(row[2], Long.parseLong(row[3])));
    }
    final Map<String, ReferenceCost> cost = new HashMap<>();
    bySelection.forEach(
        (selection, figures) -> cost.put(selection, new ReferenceCost(Map.copyOf(figures))));
    return Map.copyOf(cost);
  }

  private static List<String> repositoriesToMeasure() {
    final String only = System.getProperty("strata.elementReads.only");
    if (only == null || only.isBlank()) {
      return REPOSITORIES;
    }
    final List<String> chosen = List.of(only.split(","));
    assertThat(REPOSITORIES).as("-Dstrata.elementReads.only").containsAll(chosen);
    return REPOSITORIES.stream().filter(chosen::contains).toList();
  }

  private static double loadAverage() {
    return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
  }

  private static Settings settings(double loadAtStart, Double loadAtEnd) {
    final Map<String, Object> constants = new LinkedHashMap<>();
    constants.put("DEPENDENCY_PAGE_DEFAULT", AnalysisConstants.DEPENDENCY_PAGE_DEFAULT);
    constants.put("DEPENDENCY_PAGE_MAX", AnalysisConstants.DEPENDENCY_PAGE_MAX);
    constants.put(
        "DEPENDENCY_EVIDENCE_RESULTS_MAX", AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX);
    constants.put("DEPENDENCY_EVIDENCE_CAP", AnalysisConstants.DEPENDENCY_EVIDENCE_CAP);
    constants.put("ELEMENT_METHODS_MAX", AnalysisConstants.ELEMENT_METHODS_MAX);
    constants.put("MAX_LABEL_CHARS", AnalysisConstants.MAX_LABEL_CHARS);
    constants.put("MAX_CURSOR_CHARS", AnalysisConstants.MAX_CURSOR_CHARS);
    constants.put("MAX_FRAME_BYTES", AnalysisConstants.MAX_FRAME_BYTES);
    constants.put("SOFT_RESPONSE_BYTES", AnalysisConstants.SOFT_RESPONSE_BYTES);
    constants.put("SQLITE_READER_CONNECTIONS", AnalysisConstants.SQLITE_READER_CONNECTIONS);
    constants.put("SQLITE_CACHE_SIZE_KIB", AnalysisConstants.SQLITE_CACHE_SIZE_KIB);
    final Map<String, Object> selections = new LinkedHashMap<>();
    for (final Selection selection : Selection.values()) {
      selections.put(selection.name(), selection.payload());
    }
    final Map<String, Object> requests = new LinkedHashMap<>();
    requests.put("element.describe", "{analysisId, elementId}");
    requests.put(
        "element.dependencies",
        "{analysisId, cursor?, direction, elementId, limit}; sweep limit "
            + SWEEP_LIMIT
            + ", chains "
            + CHAIN_LIMITS);
    requests.put("element.dependencyEvidence", "{analysisId, source, target}; limit omitted");
    requests.put(
        "graph.slice", "{analysisId, level: PACKAGE} / {analysisId, level: TYPE, parentId}");
    final Map<String, Object> baselines = new LinkedHashMap<>();
    baselines.put("extraction", GRAPH_READS + " readBack and structuralGraph.counters");
    baselines.put(
        "census", "worst elements from an earlier analysis of the corpus, embedded as literals");
    baselines.put("cost", "warm medians from an earlier cost probe, embedded");
    final Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("worstWarmMicros", REFERENCE_WORST_WARM_MICROS);
    reference.put("framesPayloadBytes", new TreeMap<>(REFERENCE_FRAMES));
    final Map<String, Object> load = new LinkedHashMap<>();
    load.put("start", loadAtStart);
    load.put("end", loadAtEnd);
    return new Settings(
        constants,
        "Stages.production()",
        selections,
        requests,
        WARM_RUNS,
        RUNS,
        "the first call on a fresh reader: every idle reader closed first through"
            + " SqliteConnections.read's failure path, so the timed call opens a new"
            + " SQLITE_OPEN_READONLY reader (its pragmas included) with an empty SQLite page"
            + " cache; the OS page cache is not purged, the JIT is warm (the sweep ran first)",
        new Budgets(
            WARM_BUDGET_MICROS,
            RNF05_MICROS,
            DESCRIBE_FRAME_BUDGET,
            PAGE_500_FRAME_BUDGET,
            PAGE_2000_FRAME_BUDGET,
            EVIDENCE_FRAME_BUDGET,
            SOFT_RESPONSE_BYTES),
        baselines,
        reference,
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

  private static Transcript transcript(Path file, long frames, Path strataRoot) throws IOException {
    return new Transcript(
        strataRoot.relativize(file).toString(), frames, Files.size(file), sha256(file));
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

  private static String sha256(String text) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
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
      for (final Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }
  }

  private record End(String id, String kind, boolean external) {}

  private record OracleElement(
      String id, String kind, long in, long out, Long external, long methods) {}

  private record Census(ReadBack readBack, List<OracleElement> elements, CensusView view) {

    Targets targets() {
      return new Targets(
          view.methods().elementId(),
          view.fanIn().elementId(),
          view.out().elementId(),
          view.packageIn().elementId(),
          view.evidencePairs().stream().map(p -> new Pair(p.source(), p.target())).toList());
    }
  }

  private record CensusView(
      long packages,
      long types,
      Worst methods,
      Worst fanIn,
      WorstOut out,
      Worst packageIn,
      List<PairCount> evidencePairs) {}

  private record Worst(String elementId, long value) {}

  private record WorstOut(String elementId, long internal, long external) {}

  private record PairCount(String source, String target, long storedRows) {}

  private record Pair(String source, String target) {}

  private record Targets(
      String methodsType,
      String inType,
      String outType,
      String inPackage,
      List<Pair> evidencePairs) {}

  private record Row(String elementId, long weight, boolean external) {}

  private record MethodRow(String elementId, Integer start, Long cc) {}

  private record EvidenceKey(
      String type,
      String filePath,
      Integer line,
      String fromMethod,
      String toMethod,
      String resolution) {

    String content() {
      return type
          + "\t"
          + line
          + "\t"
          + fromMethod
          + "\t"
          + toMethod
          + "\t"
          + resolution
          + "\t"
          + filePath;
    }
  }

  private record WorstOracle(
      List<MethodRow> methods, Map<String, List<Row>> rankings, List<List<EvidenceKey>> stored) {}

  private record ChainPage(
      int rows, long frameBytes, long payloadBytes, int cursorChars, JsonNode totals) {}

  private record Chain(
      String name,
      String elementId,
      String direction,
      int limit,
      List<ChainPage> pages,
      List<Row> rows,
      String lastCursor) {}

  private record WorstReads(
      String analysisId,
      Targets targets,
      Call level1,
      Call typeSlice,
      Call describe,
      List<Chain> chains,
      List<Call> evidence,
      long frames,
      long largestFrameBytes) {}

  private record WorstResult(
      SliceCheck level1,
      SliceCheck typeSlice,
      DescribeCheck describe,
      List<ChainCheck> chains,
      List<EvidenceCheck> evidence,
      long largestFrameBytes) {

    static WorstResult of(WorstReads reads, WorstOracle oracle) throws IOException {
      final List<ChainCheck> chains = new ArrayList<>();
      for (final Chain chain : reads.chains()) {
        final String family = chain.name().substring(0, chain.name().lastIndexOf(' '));
        chains.add(ChainCheck.of(chain, oracle.rankings().get(family)));
      }
      final List<EvidenceCheck> evidence = new ArrayList<>();
      for (int index = 0; index < reads.evidence().size(); index++) {
        evidence.add(
            EvidenceCheck.of(
                reads.targets().evidencePairs().get(index),
                reads.evidence().get(index),
                oracle.stored().get(index)));
      }
      return new WorstResult(
          SliceCheck.of("graph.slice PACKAGE", reads.level1()),
          SliceCheck.of("graph.slice TYPE " + reads.targets().inPackage(), reads.typeSlice()),
          DescribeCheck.of(reads.targets().methodsType(), reads.describe(), oracle.methods()),
          List.copyOf(chains),
          List.copyOf(evidence),
          reads.largestFrameBytes());
    }
  }

  private record SliceCheck(String request, int nodes, int edges, long methodIds, long frameBytes) {

    static SliceCheck of(String request, Call call) throws IOException {
      final JsonNode slice = call.response();
      long methods = 0;
      for (final JsonNode node : slice.path("nodes")) {
        methods += node.path("id").asText().startsWith(METHOD_PREFIX) ? 1 : 0;
      }
      for (final JsonNode edge : slice.path("edges")) {
        methods += edge.path("source").asText().startsWith(METHOD_PREFIX) ? 1 : 0;
        methods += edge.path("target").asText().startsWith(METHOD_PREFIX) ? 1 : 0;
      }
      return new SliceCheck(
          request,
          slice.path("nodes").size(),
          slice.path("edges").size(),
          methods,
          call.frame().length);
    }
  }

  private record DescribeCheck(
      String elementId,
      long methodRows,
      long methodTotal,
      int methods,
      boolean orderEqualsSql,
      boolean ccEqualsSql,
      int maxSignatureCodePoints,
      long frameBytes,
      long payloadBytes) {

    static DescribeCheck of(String elementId, Call call, List<MethodRow> expected)
        throws IOException {
      final JsonNode detail = call.response();
      final JsonNode methods = detail.path("methods");
      boolean order =
          methods.size() == Math.min(expected.size(), AnalysisConstants.ELEMENT_METHODS_MAX);
      boolean cc = true;
      int longest = 0;
      for (int index = 0; index < methods.size(); index++) {
        final JsonNode method = methods.get(index);
        final String signature = method.path("signature").asText();
        longest = Math.max(longest, signature.codePointCount(0, signature.length()));
        if (index >= expected.size()) {
          order = false;
          continue;
        }
        final MethodRow row = expected.get(index);
        order &= row.elementId().equals(method.path("elementId").asText());
        cc &=
            row.cc() == null
                ? method.path("cc").isNull()
                : method.path("cc").isIntegralNumber() && method.path("cc").asLong() == row.cc();
      }
      return new DescribeCheck(
          elementId,
          expected.size(),
          detail.path("methodTotal").asLong(-1),
          methods.size(),
          order,
          cc,
          longest,
          call.frame().length,
          call.payloadBytes());
    }
  }

  private record ChainCheck(
      String name,
      String elementId,
      String direction,
      int limit,
      int pages,
      long rows,
      long expectedRows,
      JsonNode totals,
      long missing,
      long extra,
      long repeats,
      int firstRankMismatch,
      boolean pagingShape,
      long maxFrameBytes,
      int maxPageRows,
      long maxPayloadBytes,
      List<Long> frameBytes,
      String sequenceSha256) {

    static ChainCheck of(Chain chain, List<Row> ranking) {
      final JsonNode totals = chain.pages().getFirst().totals();
      final long expectedRows =
          IN.equals(chain.direction())
              ? totals.path("in").asLong()
              : totals.path("out").asLong() + totals.path("outExternal").asLong(0);
      final Set<String> listed = new HashSet<>();
      long repeats = 0;
      for (final Row row : chain.rows()) {
        repeats += listed.add(row.elementId()) ? 0 : 1;
      }
      final Set<String> sql = new HashSet<>();
      ranking.forEach(row -> sql.add(row.elementId()));
      final long missing = sql.stream().filter(id -> !listed.contains(id)).count();
      final long extra = listed.stream().filter(id -> !sql.contains(id)).count();
      int mismatch =
          chain.rows().size() == ranking.size()
              ? -1
              : Math.min(chain.rows().size(), ranking.size());
      for (int index = 0; index < Math.min(chain.rows().size(), ranking.size()); index++) {
        if (!chain.rows().get(index).equals(ranking.get(index))) {
          mismatch = index;
          break;
        }
      }
      boolean shape = true;
      ChainPage largest = chain.pages().getFirst();
      final StringBuilder sequence = new StringBuilder();
      chain
          .rows()
          .forEach(
              row ->
                  sequence.append(row.elementId()).append('\t').append(row.weight()).append('\n'));
      for (int index = 0; index < chain.pages().size(); index++) {
        final ChainPage page = chain.pages().get(index);
        final boolean last = index == chain.pages().size() - 1;
        shape &= last ? page.cursorChars() == 0 : page.cursorChars() == DEPENDENCY_CURSOR_CHARS;
        shape &= last ? page.rows() <= chain.limit() : page.rows() == chain.limit();
        shape &= page.totals().equals(totals);
        if (page.frameBytes() > largest.frameBytes()) {
          largest = page;
        }
      }
      return new ChainCheck(
          chain.name(),
          chain.elementId(),
          chain.direction(),
          chain.limit(),
          chain.pages().size(),
          chain.rows().size(),
          expectedRows,
          totals,
          missing,
          extra,
          repeats,
          mismatch,
          shape,
          largest.frameBytes(),
          largest.rows(),
          largest.payloadBytes(),
          chain.pages().stream().map(ChainPage::frameBytes).toList(),
          sha256(sequence.toString()));
    }
  }

  private record EvidenceCheck(
      String source,
      String target,
      long storedRows,
      List<String> storedTypes,
      int rows,
      List<String> wireTypes,
      Map<String, Long> perType,
      boolean orderEqualsOracle,
      boolean multisetEqualsOracle,
      long frameBytes,
      long payloadBytes) {

    static EvidenceCheck of(Pair pair, Call call, List<EvidenceKey> stored) throws IOException {
      final JsonNode evidence = call.response().path("evidence");
      final List<EvidenceKey> wire = new ArrayList<>();
      for (final JsonNode row : evidence) {
        wire.add(
            new EvidenceKey(
                row.path("type").asText(),
                row.path("filePath").isNull() ? null : row.path("filePath").asText(),
                row.path("line").isNull() ? null : row.path("line").asInt(),
                row.path("fromMethod").isNull()
                    ? null
                    : row.path("fromMethod").path("elementId").asText(),
                row.path("toMethod").isNull()
                    ? null
                    : row.path("toMethod").path("elementId").asText(),
                row.path("resolution").asText()));
      }
      final List<EvidenceKey> expected =
          selectEvidence(stored, AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX);
      final List<String> storedTypes =
          stored.stream()
              .map(EvidenceKey::type)
              .distinct()
              .sorted(Comparator.comparingInt(type -> DependencyType.valueOf(type).ordinal()))
              .toList();
      final Map<String, Long> perType = new LinkedHashMap<>();
      wire.forEach(key -> perType.merge(key.type(), 1L, Long::sum));
      return new EvidenceCheck(
          pair.source(),
          pair.target(),
          stored.size(),
          storedTypes,
          wire.size(),
          List.copyOf(perType.keySet()),
          perType,
          wire.stream()
              .map(EvidenceKey::content)
              .toList()
              .equals(expected.stream().map(EvidenceKey::content).toList()),
          multiset(wire).equals(multiset(expected)),
          call.frame().length,
          call.payloadBytes());
    }

    private static Map<EvidenceKey, Long> multiset(List<EvidenceKey> keys) {
      final Map<EvidenceKey, Long> counts = new HashMap<>();
      keys.forEach(key -> counts.merge(key, 1L, Long::sum));
      return counts;
    }
  }

  private record Report(
      int schema,
      String measuredAt,
      String baseCommit,
      String measure,
      long wallMillis,
      Settings settings,
      Environment environment,
      List<Expectation> expectations,
      List<RepositoryResult> repositories) {}

  private record Settings(
      Map<String, Object> constants,
      String plan,
      Map<String, Object> selections,
      Map<String, Object> requests,
      int warmRuns,
      int analysesPerSelection,
      String cold,
      Budgets budgets,
      Map<String, Object> baselines,
      Map<String, Object> reference,
      String corpus,
      Map<String, Object> loadAverage1m) {}

  private record Budgets(
      long warmMedianMicros,
      long rnf05Micros,
      long describeFrameBytes,
      long page500FrameBytes,
      long page2000FrameBytes,
      long evidenceFrameBytes,
      long softResponseBytes) {}

  private record Expectation(
      String id, String claim, String expected, String measured, boolean met) {}

  private record RepositoryResult(String name, String commitId, List<SelectionResult> selections) {}

  private record SelectionResult(
      String selection,
      Map<String, Object> payload,
      List<RunResult> runs,
      ReadBack readBack,
      CensusView census,
      WorstResult worst,
      List<Latency> latency,
      TimedPhase timedPhase,
      Determinism determinism) {}

  private record RunResult(
      int run,
      String analysisId,
      String terminalEvent,
      long analysisWallMillis,
      Map<String, Long> stageDurationsMs,
      StructuralGraph structuralGraph,
      long cacheBytes,
      String keptCache,
      Load loadAverage1m,
      Timestamps at,
      SweepRun sweep,
      Transcript worstTranscript,
      @JsonIgnore ReadBack readBack) {

    static RunResult of(
        int run,
        AnalysisId id,
        List<Frame> frames,
        long cacheBytes,
        Load load,
        Timestamps at,
        SweepRun sweep,
        Transcript worstTranscript,
        Census census,
        String keptCache) {
      final long started =
          frames.stream()
              .filter(frame -> "analysis.started".equals(frame.event()))
              .findFirst()
              .orElseThrow()
              .nanos();
      final Frame terminal = frames.stream().filter(Frame::isTerminal).findFirst().orElseThrow();
      final Map<String, Long> durations = new LinkedHashMap<>();
      JsonNode graph = null;
      for (final Frame frame : frames) {
        if (STAGE_COMPLETED.equals(frame.event())) {
          durations.put(frame.stage(), frame.payload().path("durationMs").asLong());
          if (GRAPH_STAGE.equals(frame.stage())) {
            graph = frame.payload();
          }
        }
      }
      Objects.requireNonNull(graph, "nenhum stage.completed do STRUCTURAL_GRAPH");
      return new RunResult(
          run,
          id.value(),
          terminal.event(),
          (terminal.nanos() - started) / NANOS_PER_MILLI,
          durations,
          new StructuralGraph(
              graph.path("status").asText(),
              graph.path("durationMs").asLong(),
              graph.path("counters")),
          cacheBytes,
          keptCache,
          load,
          at,
          sweep,
          worstTranscript,
          census.readBack());
    }
  }

  private record StructuralGraph(String status, long durationMs, JsonNode counters) {}

  private record Load(double start, double end) {}

  private record Timestamps(String analysisStarted, String readsStarted, String readsEnded) {}

  private record Transcript(String file, long frames, long bytes, String sha256) {}

  private record ReadBack(
      Elements elements, Dependencies dependencies, Map<String, Long> metricRows) {}

  private record Violations(long count, List<String> examples) {}

  private record LargestFrame(String request, long frameBytes) {}

  private record SweepChecks(
      Violations totalsVsFans,
      Violations totalsVsSql,
      Violations outExternalNullIffPackage,
      Violations totalsBothDirections,
      Violations cursorIffRowsRemain,
      Violations methodTotals) {}

  private record SweepRun(
      long elements,
      long packages,
      long types,
      long requests,
      long millis,
      Map<String, Long> describeErrors,
      Map<String, Long> pageErrors,
      SweepChecks checks,
      LargestFrame largestDescribe,
      LargestFrame largestPage,
      long largestFrameBytes,
      Transcript transcript,
      String totalsSha256) {

    SweepRun withTranscript(Transcript recorded) {
      return new SweepRun(
          elements,
          packages,
          types,
          requests,
          millis,
          describeErrors,
          pageErrors,
          checks,
          largestDescribe,
          largestPage,
          largestFrameBytes,
          recorded,
          totalsSha256);
    }
  }

  private record ReferenceFigure(String elementId, long warmMedianMicros) {}

  private record ReferenceRatio(String elementId, long warmMedianMicros, double ratio) {}

  private record Latency(
      String request,
      String method,
      String kind,
      long coldMicros,
      long warmMedianMicros,
      long warmMaxMicros,
      List<Long> warmRunsMicros,
      long frameBytes,
      long payloadBytes,
      boolean identicalResponses,
      ReferenceRatio reference) {}

  private record TimedPhase(
      long gcCollections, long gcMillis, Load load, String started, String ended) {}

  private record Determinism(
      boolean worstTranscriptsIdentical,
      String worstSha256Run1,
      String worstSha256Run2,
      long worstFirstDifferentLine,
      boolean sweepTranscriptsIdentical,
      String sweepSha256Run1,
      String sweepSha256Run2,
      long sweepFirstDifferentLine,
      boolean sweepTotalsIdentical,
      boolean repeatsIdentical,
      boolean censusIdentical) {}

  private record Measured(
      RunResult run,
      Census census,
      WorstResult worst,
      List<Latency> latency,
      TimedPhase timedPhase) {}
}
