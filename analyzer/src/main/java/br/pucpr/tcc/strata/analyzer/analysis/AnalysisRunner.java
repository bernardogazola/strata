package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot.StageSnapshot;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageRecord;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class AnalysisRunner {

  private static final List<AnalysisStage> SEQUENTIAL =
      List.of(
          AnalysisStage.STRUCTURAL_GRAPH,
          AnalysisStage.GIT_HISTORY_DETAILED,
          AnalysisStage.EVOLUTIONARY_COUPLING,
          AnalysisStage.HOTSPOT_PROFILES,
          AnalysisStage.GUIDED_TOUR);

  private static final String EMPTY_COUNTERS = StageCounters.none().toJson();

  private final AnalysisRegistry registry;
  private final RegistrySettings settings;
  private final ProgressThrottle throttle;
  private final RunPrologue prologue;
  private final ExecutorService cpuPool;

  AnalysisRunner(
      AnalysisRegistry registry,
      RegistrySettings settings,
      ProgressThrottle throttle,
      ExecutorService cpuPool) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.cpuPool = Objects.requireNonNull(cpuPool, "cpuPool");
    this.prologue = new RunPrologue(registry, settings, throttle);
  }

  void run(Run run) {
    final AtomicReference<Throwable> siblingFailure = new AtomicReference<>();
    try {
      run.events().started();
      final RunInputs inputs = prologue.run(run);
      runPair(run, inputs, siblingFailure);
      for (final AnalysisStage stage : SEQUENTIAL) {
        run.token().throwIfCancelled();
        runStage(run, inputs, stage, run.token(), false);
      }
      run.token().throwIfCancelled();
      final long finishedAt = runStage(run, inputs, AnalysisStage.PERSISTENCE, run.token(), true);
      completed(run, finishedAt);
    } catch (RuntimeException | SQLException | IOException | CacheUnavailableException stopped) {
      if (siblingFailure.get() instanceof Error fatal) {
        died(run, fatal);
        throw fatal;
      }
      interrupted(run, stopped, siblingFailure.get());
    } catch (Error fatal) {
      died(run, fatal);
      throw fatal;
    }
  }

  private void runPair(Run run, RunInputs inputs, AtomicReference<Throwable> siblingFailure)
      throws SQLException, IOException {
    run.token().throwIfCancelled();
    final CancellationToken mainToken = run.token().child();
    final CancellationToken siblingToken = run.token().child();
    final Future<?> sibling =
        cpuPool.submit(
            () -> {
              try {
                runStage(run, inputs, AnalysisStage.GIT_HISTORY_CHEAP, siblingToken, false);
              } catch (Throwable failure) {
                siblingFailure.set(failure);
                mainToken.cancel();
              }
            });
    boolean mainCompleted = false;
    try {
      runStage(run, inputs, AnalysisStage.SOURCE_ANALYSIS, mainToken, false);
      mainCompleted = true;
    } finally {
      if (!mainCompleted) {
        siblingToken.cancel();
      }
      join(sibling);
    }
    rethrow(siblingFailure.get());
  }

  private static void join(Future<?> sibling) {
    while (true) {
      try {
        sibling.get();
        return;
      } catch (InterruptedException interrupted) {
        Log.warn(
            "a thread da análise foi interrompida ao aguardar GIT_HISTORY_CHEAP; segue aguardando");
      } catch (ExecutionException | CancellationException unexpected) {
        Log.error("a tarefa GIT_HISTORY_CHEAP terminou de forma anormal", unexpected);
        return;
      }
    }
  }

  private static void rethrow(Throwable failure) throws SQLException, IOException {
    switch (failure) {
      case null -> {}
      case Error fatal -> throw fatal;
      case RuntimeException unchecked -> throw unchecked;
      case SQLException database -> throw database;
      case IOException unread -> throw unread;
      default -> throw new IllegalStateException("exceção checada inesperada", failure);
    }
  }

  private long runStage(
      Run run, RunInputs inputs, AnalysisStage stage, CancellationToken token, boolean finalizing)
      throws SQLException, IOException {
    final Run.Binding bound = run.binding();
    final LongSupplier nanos = settings.nanoClock();
    final long startedAt = settings.clock().millis();
    final long startNanos = nanos.getAsLong();
    run.stageRunning(stage, startedAt);
    final StageOutcome outcome;
    final long durationMs;
    final long finishedAt;
    try {
      write(bound, RunPrologue.row(stage, StageSnapshot.running(startedAt)), false, 0);
      outcome = settings.plan().executor(stage).execute(context(run, inputs, stage, token));
      if (finalizing && !run.beginFinalizing()) {
        throw new CancellationException("análise cancelada");
      }
      durationMs = RunPrologue.millisSince(nanos, startNanos);
      finishedAt = finishInstant(run);
      write(
          bound,
          new StageRecord(
              stage, outcome.status(), startedAt, durationMs, outcome.counters().toJson()),
          finalizing,
          finishedAt);
    } catch (RuntimeException | SQLException | IOException failure) {
      final long elapsed = RunPrologue.millisSince(nanos, startNanos);
      final StageStatus status = interruptedStatus(run, token);
      if (!RunFailures.isCorruption(failure)) {
        writeQuietly(bound, new StageRecord(stage, status, startedAt, elapsed, EMPTY_COUNTERS));
      }
      run.stageEnded(stage, status, startedAt, elapsed, StageCounters.none());
      throw failure;
    }
    run.stageEnded(stage, outcome.status(), startedAt, durationMs, outcome.counters());
    return finishedAt;
  }

  static StageStatus interruptedStatus(Run run, CancellationToken token) {
    return token.isCancelled() || run.cancelRequested()
        ? StageStatus.CANCELLED
        : StageStatus.FAILED;
  }

  private StageContext context(
      Run run, RunInputs inputs, AnalysisStage stage, CancellationToken token) {
    final Run.Binding bound = run.binding();
    return new StageContext(
        run.id(),
        stage,
        inputs,
        bound.connections(),
        bound.analysisPk(),
        token,
        new ProgressReporter(run.id(), stage, throttle, run.events()::progress),
        run.events(),
        run);
  }

  private static void write(Run.Binding bound, StageRecord row, boolean finish, long finishedAt)
      throws SQLException {
    bound
        .connections()
        .write(
            connection -> {
              StageDao.upsert(connection, bound.analysisPk(), row);
              if (finish) {
                AnalysisDao.finish(
                    connection,
                    bound.analysisPk(),
                    AnalysisStatus.COMPLETED,
                    finishedAt,
                    null,
                    null);
              }
              return null;
            });
  }

  private static void writeQuietly(Run.Binding bound, StageRecord row) {
    try {
      write(bound, row, false, 0);
    } catch (SQLException | RuntimeException failure) {
      Log.error("a linha de " + row.stage() + " não conseguiu gravar " + row.status(), failure);
    }
  }

  private void completed(Run run, long finishedAt) {
    run.endPhase();
    run.ended(AnalysisStatus.COMPLETED, finishedAt, null);
    final Completeness completeness = run.snapshot().completeness();
    release(run);
    run.events().completed(completeness);
    Log.info("análise " + run.id().value() + " COMPLETED");
  }

  private void interrupted(Run run, Throwable original, Throwable siblingFailure) {
    final boolean runCancelled = run.endPhase() || run.token().isCancelled();
    final RunFailures.Verdict verdict =
        RunFailures.classify(original, runCancelled, siblingFailure);
    if (verdict.corruptCache()) {
      registry.rebuild(run);
    }
    final long finishedAt = finishInstant(run);
    switch (verdict) {
      case RunFailures.Cancelled cancelled -> {
        if (!(original instanceof CancellationException)) {
          Log.warn("a análise " + run.id().value() + " falhou depois de cancelada: " + original);
        }
        if (!cancelled.corruptCache()) {
          finishQuietly(run, AnalysisStatus.CANCELLED, finishedAt, null);
        }
        run.ended(AnalysisStatus.CANCELLED, finishedAt, null);
        final Completeness completeness = run.snapshot().completeness();
        release(run);
        run.events().cancelled(completeness);
        Log.info("análise " + run.id().value() + " CANCELLED");
      }
      case RunFailures.Failed(ProtocolError error, boolean corruptCache) -> {
        if (original instanceof StageFailedException) {
          Log.warn("análise " + run.id().value() + " FAILED: " + error.code());
        } else {
          Log.error("análise " + run.id().value() + " FAILED com " + error.code(), original);
        }
        if (!corruptCache) {
          finishQuietly(run, AnalysisStatus.FAILED, finishedAt, error);
        }
        failed(run, finishedAt, error);
      }
    }
  }

  private void died(Run run, Error fatal) {
    try {
      run.endPhase();
      final long now = finishInstant(run);
      final Run.Binding bound = run.binding();
      for (final Map.Entry<AnalysisStage, StageSnapshot> entry :
          run.snapshot().stages().entrySet()) {
        final StageSnapshot stage = entry.getValue();
        if (stage.status() == StageStatus.RUNNING) {
          final long elapsed = Math.max(0, now - stage.startedAtMillis());
          if (bound != null && bound.hasRow()) {
            writeQuietly(
                bound,
                new StageRecord(
                    entry.getKey(),
                    StageStatus.FAILED,
                    stage.startedAtMillis(),
                    elapsed,
                    EMPTY_COUNTERS));
          }
          run.stageEnded(
              entry.getKey(),
              StageStatus.FAILED,
              stage.startedAtMillis(),
              elapsed,
              StageCounters.none());
        }
      }
      final ProtocolError error = ErrorCodes.internalUnexpected(fatal.getClass().getName());
      finishQuietly(run, AnalysisStatus.FAILED, now, error);
      failed(run, now, error);
    } catch (RuntimeException secondary) {
      fatal.addSuppressed(secondary);
      release(run);
    }
  }

  void launchFailed(Run run, Throwable refused) {
    Log.error("a análise " + run.id().value() + " não conseguiu iniciar a sua thread", refused);
    run.endPhase();
    run.events().started();
    failed(run, finishInstant(run), ErrorCodes.internalUnexpected(refused.getClass().getName()));
  }

  private long finishInstant(Run run) {
    return Math.max(settings.clock().millis(), run.snapshot().startedAtMillis());
  }

  private void failed(Run run, long finishedAt, ProtocolError error) {
    run.ended(AnalysisStatus.FAILED, finishedAt, error);
    release(run);
    run.events().failed(error);
  }

  private static void finishQuietly(
      Run run, AnalysisStatus status, long finishedAt, ProtocolError error) {
    final Run.Binding bound = run.binding();
    if (bound == null || !bound.hasRow()) {
      return;
    }
    try {
      bound
          .connections()
          .write(
              connection -> {
                AnalysisDao.finish(
                    connection,
                    bound.analysisPk(),
                    status,
                    finishedAt,
                    error == null ? null : error.code(),
                    error == null ? null : error.detail());
                return null;
              });
    } catch (SQLException | RuntimeException failure) {
      Log.error("a análise " + run.id().value() + " não conseguiu gravar " + status, failure);
    }
  }

  private void release(Run run) {
    run.events().logCappedWarnings();
    throttle.forget(run.id());
    registry.release(run);
  }
}
