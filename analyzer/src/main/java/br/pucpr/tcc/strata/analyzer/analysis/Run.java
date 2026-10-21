package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot.StageSnapshot;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryKey;
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class Run implements CompletenessRecorder {

  enum Phase {
    RUNNING,
    CANCEL_REQUESTED,
    FINALIZING,
    ENDED
  }

  record Binding(RepositoryCache cache, RepositoryKey key, long analysisPk) {

    Binding {
      Objects.requireNonNull(cache, "cache");
      Objects.requireNonNull(key, "key");
      if (analysisPk < 0) {
        throw new IllegalArgumentException("analysisPk deve ser >= 0: " + analysisPk);
      }
    }

    boolean hasRow() {
      return analysisPk > 0;
    }

    Binding withRow(long pk) {
      return new Binding(cache, key, pk);
    }

    SqliteConnections connections() {
      return cache.connections();
    }
  }

  private final AnalysisId id;
  private final String repositoryPath;
  private final Path claimedRoot;
  private final AnalysisConfiguration configuration;
  private final CancellationToken token = CancellationToken.create();
  private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
  private final AtomicReference<RunSnapshot> snapshot;
  private final AtomicBoolean launched = new AtomicBoolean();
  private final RunEvents events;
  private final Consumer<Run> body;
  private final BiConsumer<Run, Throwable> launchFailed;
  private volatile Thread thread;
  private volatile Binding binding;

  Run(
      AnalysisId id,
      String repositoryPath,
      Path claimedRoot,
      AnalysisConfiguration configuration,
      long startedAtMillis,
      FrameSink sink,
      Consumer<Run> body,
      BiConsumer<Run, Throwable> launchFailed) {
    this.id = Objects.requireNonNull(id, "id");
    this.repositoryPath = Objects.requireNonNull(repositoryPath, "repositoryPath");
    this.claimedRoot = Objects.requireNonNull(claimedRoot, "claimedRoot");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.snapshot = new AtomicReference<>(RunSnapshot.started(id, startedAtMillis));
    this.events = new RunEvents(id, sink, token);
    this.body = Objects.requireNonNull(body, "body");
    this.launchFailed = Objects.requireNonNull(launchFailed, "launchFailed");
  }

  static String threadName(AnalysisId id) {
    final String value = id.value();
    return "strata-analysis-" + value.substring(value.length() - 8);
  }

  void launch() {
    if (!launched.compareAndSet(false, true)) {
      return;
    }
    try {
      final Thread created = new Thread(() -> body.accept(this), threadName(id));
      created.setDaemon(true);
      created.setUncaughtExceptionHandler(Run::logUncaught);
      thread = created;
      created.start();
    } catch (RuntimeException | OutOfMemoryError refused) {
      thread = null;
      launchFailed.accept(this, refused);
    }
  }

  boolean discardIfUnlaunched() {
    return launched.compareAndSet(false, true);
  }

  private static void logUncaught(Thread dead, Throwable failure) {
    Log.error("a thread de análise " + dead.getName() + " morreu", failure);
  }

  AnalysisId id() {
    return id;
  }

  String repositoryPath() {
    return repositoryPath;
  }

  Path claimedRoot() {
    return claimedRoot;
  }

  AnalysisConfiguration configuration() {
    return configuration;
  }

  CancellationToken token() {
    return token;
  }

  RunEvents events() {
    return events;
  }

  Thread thread() {
    return thread;
  }

  RunSnapshot snapshot() {
    return snapshot.get();
  }

  Binding binding() {
    return binding;
  }

  void bind(Binding bound) {
    binding = Objects.requireNonNull(bound, "bound");
  }

  AnalysisRegistry.CancelResult requestCancel() {
    if (phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED)) {
      return AnalysisRegistry.CancelResult.requested(token::cancel);
    }
    return phase.get() == Phase.CANCEL_REQUESTED
        ? AnalysisRegistry.CancelResult.alreadyRequested()
        : AnalysisRegistry.CancelResult.refused();
  }

  void requestStop() {
    phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED);
    token.cancel();
  }

  boolean cancelRequested() {
    return phase.get() == Phase.CANCEL_REQUESTED;
  }

  boolean beginFinalizing() {
    return phase.compareAndSet(Phase.RUNNING, Phase.FINALIZING);
  }

  boolean endPhase() {
    return phase.getAndSet(Phase.ENDED) == Phase.CANCEL_REQUESTED;
  }

  void stageRunning(AnalysisStage stage, long startedAtMillis) {
    snapshot.updateAndGet(
        current -> current.withStage(stage, StageSnapshot.running(startedAtMillis)));
  }

  void stageEnded(
      AnalysisStage stage,
      StageStatus status,
      long startedAtMillis,
      long durationMs,
      StageCounters counters) {
    snapshot.updateAndGet(
        current ->
            current.withStage(
                stage, StageSnapshot.finished(status, startedAtMillis, durationMs, counters)));
    events.stageCompleted(stage, status, durationMs, counters);
  }

  void headCommitRead(String commitId) {
    snapshot.updateAndGet(current -> current.withHeadCommitId(commitId));
  }

  void ended(AnalysisStatus outcome, long finishedAtMillis, ProtocolError error) {
    snapshot.updateAndGet(current -> current.finished(outcome, finishedAtMillis, error));
  }

  void boundCompleteness(Completeness completeness) {
    snapshot.updateAndGet(current -> current.withCompleteness(completeness));
  }

  @Override
  public synchronized void recordSourceFiles(int total, int skipped, int parseFailures)
      throws SQLException {
    final Completeness current = snapshot().completeness();
    record(
        new Completeness(
            total,
            skipped,
            parseFailures,
            current.shallowClone(),
            current.historyTruncatedAtCommits()));
  }

  @Override
  public synchronized void recordHistoryTruncatedAt(int commits) throws SQLException {
    final Completeness current = snapshot().completeness();
    record(
        new Completeness(
            current.totalFiles(),
            current.skippedFiles(),
            current.parseFailures(),
            current.shallowClone(),
            commits));
  }

  private void record(Completeness counted) throws SQLException {
    final Binding bound = binding;
    if (bound == null || !bound.hasRow()) {
      throw new IllegalStateException(
          "a análise " + id.value() + " ainda não tem linha em analysis");
    }
    bound
        .connections()
        .write(
            connection -> {
              AnalysisDao.updateCounters(connection, bound.analysisPk(), counted.toCounters());
              return null;
            });
    snapshot.updateAndGet(current -> current.withCompleteness(counted));
  }
}
