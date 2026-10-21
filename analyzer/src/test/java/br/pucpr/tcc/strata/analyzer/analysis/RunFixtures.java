package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.sqlite.SQLiteConfig;

final class RunFixtures {

  static final AnalysisId FIXTURE_ID = new AnalysisId("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  static final Instant FIXED_INSTANT = TestRepositories.FIXTURE_IDENT.getWhenAsInstant();

  static final String ANALYZER_VERSION = "0.0.0-test";

  static final Duration AWAIT = Duration.ofSeconds(60);

  static final String DEFAULT_CONFIG_JSON =
      "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":false,"
          + "\"sourceRootIds\":null}";

  static final String DEFAULT_CONFIG_HASH =
      "c8ddc5aec20c88456feb94818eb0c8fe6c9838069031742b38a6b5dc4edffb1d";

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final String ID_PREFIX = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V";

  private static final int FIXTURE_SUFFIX = 32 + 28;

  private RunFixtures() {}

  static AnalysisId idAt(int n) {
    final int suffix = FIXTURE_SUFFIX + n;
    return new AnalysisId(
        ID_PREFIX + CROCKFORD.charAt(suffix / 32 % 32) + CROCKFORD.charAt(suffix % 32));
  }

  static Supplier<AnalysisId> ids() {
    final AtomicInteger next = new AtomicInteger();
    return () -> idAt(next.getAndIncrement());
  }

  static String runThreadName(AnalysisId id) {
    return "strata-analysis-" + id.value().substring(id.value().length() - 8);
  }

  static final class ManualNanoClock implements LongSupplier {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final long step;

    private ManualNanoClock(long step) {
      this.step = step;
    }

    static ManualNanoClock frozen() {
      return new ManualNanoClock(0);
    }

    static ManualNanoClock stepping(Duration step) {
      return new ManualNanoClock(step.toNanos());
    }

    @Override
    public long getAsLong() {
      return now.getAndAdd(step);
    }
  }

  static AnalysisConfiguration defaults() {
    return new AnalysisConfiguration(false, HistoryScope.ALL, null, null, false);
  }

  static RegistrySettings settings(
      Path dataDir, FrameSink sink, StagePlan plan, LongSupplier nanoClock) throws IOException {
    return RegistrySettings.production(PersistenceRoot.prepare(dataDir), sink, ANALYZER_VERSION)
        .withClock(InstantSource.fixed(FIXED_INSTANT))
        .withNanoClock(nanoClock)
        .withIds(ids())
        .withPlan(plan);
  }

  static StagePlan lightPlan() {
    final Map<AnalysisStage, StageExecutor> executors = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : StagePlan.SPI_STAGES) {
      executors.put(stage, SkippedStage.INSTANCE);
    }
    executors.put(
        AnalysisStage.PERSISTENCE, context -> StageOutcome.completed(StageCounters.none()));
    return new StagePlan(executors);
  }

  static AnalysisId launch(
      AnalysisRegistry registry, Path repository, AnalysisConfiguration configuration) {
    final StartResult result = registry.start(repository.toString(), repository, configuration);
    assertThat(result)
        .as("o registro tem de aceitar o começo: nenhuma análise roda neste registro")
        .isInstanceOf(StartResult.Started.class);
    final StartResult.Started started = (StartResult.Started) result;
    started.launch().run();
    return started.analysisId();
  }

  static Path multiModule(Path parent) throws Exception {
    final Path repository = parent.toRealPath().resolve("multi-module");
    final var commit = TestRepositories.reproduceCorpusFixture("multi-module", repository);
    assertThat(commit.name())
        .as("a reprodução tem de dar o commit que o make-fixtures.sh gravou")
        .isEqualTo(TestRepositories.corpusCommitId("multi-module"));
    return repository;
  }

  static String multiModuleCommit() throws IOException {
    return TestRepositories.corpusCommitId("multi-module");
  }

  static Thread runThread(RecordingFrameSink sink, AnalysisId id) throws InterruptedException {
    return sink.await(
            frame -> frame.isOf(id) && "analysis.started".equals(frame.event()),
            AWAIT,
            "o analysis.started de " + id)
        .thread();
  }

  static void awaitRunEnd(AnalysisId id) throws InterruptedException {
    final String nome = runThreadName(id);
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (nome.equals(thread.getName())) {
        awaitDeath(thread);
      }
    }
  }

  static void awaitDeath(Thread thread) throws InterruptedException {
    thread.join(AWAIT.toMillis());
    assertThat(thread.isAlive()).as("a thread " + thread.getName() + " ainda vive").isFalse();
  }

  static boolean awaitLatch(CountDownLatch latch, Duration timeout) {
    try {
      return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrompido dentro de um estágio de teste", interrupted);
    }
  }

  static StageExecutor pollingUntilCancelled(CountDownLatch entered, Duration limit) {
    return context -> {
      entered.countDown();
      final CountDownLatch never = new CountDownLatch(1);
      final long deadline = System.nanoTime() + limit.toNanos();
      while (System.nanoTime() < deadline) {
        context.cancellation().throwIfCancelled();
        awaitLatch(never, Duration.ofMillis(10));
      }
      return StageOutcome.completed(StageCounters.none());
    };
  }

  static Path database(Path dataDir, Path repository, String rootCommitId) {
    final RepositoryIdentity identity =
        RepositoryIdentity.of(repository.toString(), PathFlavor.host(), rootCommitId);
    return dataDir
        .resolve(PersistenceRoot.DIRECTORY_NAME)
        .resolve(identity.key().value())
        .resolve(RepositoryCache.DATABASE_FILE);
  }

  static Connection readOnly(Path database) throws SQLException {
    assertThat(database)
        .as("o cache deveria estar na pasta da chave derivada com o commit raiz")
        .isRegularFile();
    final SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    return DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
  }

  static List<Map<String, Object>> rows(Path database, String sql, Object... arguments)
      throws SQLException {
    try (Connection connection = readOnly(database);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < arguments.length; i++) {
        statement.setObject(i + 1, arguments[i]);
      }
      final List<Map<String, Object>> rows = new ArrayList<>();
      try (ResultSet result = statement.executeQuery()) {
        final ResultSetMetaData meta = result.getMetaData();
        while (result.next()) {
          final Map<String, Object> row = new LinkedHashMap<>();
          for (int column = 1; column <= meta.getColumnCount(); column++) {
            row.put(meta.getColumnLabel(column), result.getObject(column));
          }
          rows.add(row);
        }
      }
      return rows;
    }
  }

  static long count(Path database, String sql, Object... arguments) throws SQLException {
    return ((Number) rows(database, sql, arguments).get(0).values().iterator().next()).longValue();
  }

  static Optional<Map<String, Object>> analysisRow(Path database, AnalysisId id)
      throws SQLException {
    if (!Files.isRegularFile(database)) {
      return Optional.empty();
    }
    final List<Map<String, Object>> found =
        rows(database, "SELECT * FROM analysis WHERE analysis_id = ?", id.value());
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  static Map<String, Map<String, Object>> stageRows(Path database, AnalysisId id)
      throws SQLException {
    final Map<String, Map<String, Object>> byStage = new TreeMap<>();
    for (final Map<String, Object> row :
        rows(
            database,
            "SELECT s.stage, s.status, s.started_at, s.duration_ms, s.counters_json"
                + " FROM analysis_stage s JOIN analysis a ON a.id = s.analysis_pk"
                + " WHERE a.analysis_id = ?",
            id.value())) {
      byStage.put((String) row.get("stage"), row);
    }
    return byStage;
  }

  static Map<String, TreeSet<String>> elementsByFile(Path database, AnalysisId id)
      throws SQLException {
    final Map<String, TreeSet<String>> byFile = new TreeMap<>();
    for (final Map<String, Object> row :
        rows(
            database,
            "SELECT f.relative_path, e.element_id FROM code_element e"
                + " JOIN source_file f ON f.id = e.file_pk"
                + " JOIN analysis a ON a.id = e.analysis_pk"
                + " WHERE a.analysis_id = ? AND e.is_external = 0"
                + " AND e.kind IN ('TYPE', 'METHOD')",
            id.value())) {
      byFile
          .computeIfAbsent((String) row.get("relative_path"), path -> new TreeSet<>())
          .add((String) row.get("element_id"));
    }
    return byFile;
  }

  static long orphanDependencies(Path database, AnalysisId id) throws SQLException {
    return count(
        database,
        "SELECT count(*) FROM dependency d JOIN analysis a ON a.id = d.analysis_pk"
            + " LEFT JOIN code_element s ON s.id = d.source_pk"
            + " LEFT JOIN code_element t ON t.id = d.target_pk"
            + " WHERE a.analysis_id = ? AND (s.id IS NULL OR t.id IS NULL)",
        id.value());
  }

  static long fileElements(Path database, AnalysisId id) throws SQLException {
    return count(
        database,
        "SELECT count(*) FROM code_element e JOIN analysis a ON a.id = e.analysis_pk"
            + " WHERE a.analysis_id = ? AND e.kind = 'FILE'",
        id.value());
  }
}
