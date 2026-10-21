package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.persistence.CacheOpening;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryKey;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AnalysisRegistry implements AutoCloseable {

  private final RegistrySettings settings;

  private final Map<AnalysisId, Run> runs = new ConcurrentHashMap<>();

  private final AtomicReference<Run> active = new AtomicReference<>();

  final Object slot = new Object();

  private final Map<RepositoryKey, RepositoryCache> caches = new HashMap<>();

  private final AtomicBoolean closed = new AtomicBoolean();
  private final ExecutorService cpuPool;
  private final ProgressThrottle throttle;
  private final AnalysisRunner runner;

  public AnalysisRegistry(RegistrySettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.cpuPool =
        Executors.newFixedThreadPool(settings.cpuPoolThreads(), cpuThreads(new AtomicInteger()));
    this.throttle =
        new ProgressThrottle(
            settings.nanoClock(), Duration.ofMillis(AnalysisConstants.PROGRESS_THROTTLE_MILLIS));
    this.runner = new AnalysisRunner(this, settings, throttle, cpuPool);
  }

  private static ThreadFactory cpuThreads(AtomicInteger numbered) {
    return task -> {
      final Thread thread = new Thread(task, "strata-cpu-" + numbered.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  public StartResult start(
      String repositoryPath, Path claimedRoot, AnalysisConfiguration configuration) {
    Objects.requireNonNull(repositoryPath, "repositoryPath");
    Objects.requireNonNull(claimedRoot, "claimedRoot");
    Objects.requireNonNull(configuration, "configuration");
    synchronized (slot) {
      if (closed.get() || active.get() != null) {
        return new StartResult.AlreadyRunning();
      }
      final AnalysisId id = settings.ids().get();
      final Run run =
          new Run(
              id,
              repositoryPath,
              claimedRoot,
              configuration,
              settings.clock().millis(),
              settings.sink(),
              runner::run,
              runner::launchFailed);
      runs.put(id, run);
      active.set(run);
      return new StartResult.Started(id, run::launch);
    }
  }

  public CancelResult cancel(AnalysisId id) {
    final Run run = runs.get(Objects.requireNonNull(id, "id"));
    return run == null ? CancelResult.refused() : run.requestCancel();
  }

  public Optional<RunSnapshot> status(AnalysisId id) {
    return Optional.ofNullable(runs.get(Objects.requireNonNull(id, "id"))).map(Run::snapshot);
  }

  public Optional<ReadableAnalysis> readable(AnalysisId id) {
    final Run run = runs.get(Objects.requireNonNull(id, "id"));
    if (run == null) {
      return Optional.empty();
    }
    final AnalysisStatus status = run.snapshot().status();
    final Run.Binding bound = run.binding();
    final boolean servable = status == AnalysisStatus.RUNNING || status == AnalysisStatus.COMPLETED;
    if (!servable || bound == null || !bound.hasRow()) {
      return Optional.empty();
    }
    synchronized (caches) {
      if (closed.get() || caches.get(bound.key()) != bound.cache()) {
        return Optional.empty();
      }
    }
    return Optional.of(new ReadableAnalysis(id, bound.analysisPk(), bound.connections()));
  }

  @Override
  public void close() {
    synchronized (slot) {
      if (closed.get()) {
        return;
      }
      final Run current = active.get();
      if (current != null && current.discardIfUnlaunched()) {
        runs.remove(current.id());
        release(current);
      } else if (current != null) {
        current.requestStop();
      }
      closed.set(true);
    }
    final Set<RepositoryKey> stillInUse = awaitRuns(settings.runStopWait());
    closeCaches(stillInUse);
    cpuPool.shutdownNow();
  }

  private Set<RepositoryKey> awaitRuns(Duration wait) {
    final long deadline = System.nanoTime() + wait.toNanos();
    final Set<RepositoryKey> stillInUse = new HashSet<>();
    for (final Run run : runs.values()) {
      final Thread thread = run.thread();
      if (thread == null || !thread.isAlive()) {
        continue;
      }
      if (!joined(thread, deadline)) {
        Log.error(
            "a análise "
                + run.id().value()
                + " não parou em "
                + wait.toMillis()
                + " ms; o cache dela fica aberto até o processo terminar");
        final Run.Binding bound = run.binding();
        if (bound != null) {
          stillInUse.add(bound.key());
        }
      }
    }
    return stillInUse;
  }

  private static boolean joined(Thread thread, long deadlineNanos) {
    try {
      thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
    } catch (InterruptedException interrupted) {
      Log.warn("o fechamento das análises foi interrompido ao aguardar " + thread.getName());
    }
    return !thread.isAlive();
  }

  private void closeCaches(Set<RepositoryKey> stillInUse) {
    final List<RepositoryCache> closing = new ArrayList<>();
    synchronized (caches) {
      caches.forEach(
          (key, cache) -> {
            if (!stillInUse.contains(key)) {
              closing.add(cache);
            }
          });
      caches.keySet().removeIf(key -> !stillInUse.contains(key));
    }
    for (final RepositoryCache cache : closing) {
      try {
        cache.close();
      } catch (RuntimeException failure) {
        Log.error("falha ao fechar o cache de " + cache.identity().key().value(), failure);
      }
    }
  }

  RepositoryCache cacheFor(RepositoryIdentity identity, Path realRoot, RunEvents events)
      throws CacheUnavailableException {
    synchronized (caches) {
      if (closed.get()) {
        throw new CancellationException("análise cancelada");
      }
      final RepositoryCache existing = caches.get(identity.key());
      if (existing != null) {
        if (!existing.identity().equals(identity)) {
          throw new IllegalStateException(
              "a chave de repositório " + identity.key().value() + " colide com outra identidade");
        }
        return existing;
      }
    }
    final CacheOpening opening;
    try {
      opening =
          RepositoryCache.open(
              settings.persistenceRoot(),
              identity,
              realRoot,
              settings.analyzerVersion(),
              settings.clock());
    } catch (CacheUnavailableException refused) {
      refused.discarded().ifPresent(events::cacheInvalidated);
      throw refused;
    }
    if (opening instanceof CacheOpening.Rebuilt(RepositoryCache ignored, CacheInvalidation why)) {
      events.cacheInvalidated(why);
    }
    synchronized (caches) {
      if (closed.get()) {
        opening.cache().close();
        throw new CancellationException("análise cancelada");
      }
      caches.put(identity.key(), opening.cache());
    }
    return opening.cache();
  }

  Rebuild rebuild(Run run) {
    final Run.Binding bound = run.binding();
    if (bound == null) {
      return Rebuild.NOTHING_RENAMED;
    }
    try {
      final CacheOpening.Rebuilt rebuilt = bound.cache().rebuildAfterCorruption();
      synchronized (caches) {
        if (closed.get()) {
          rebuilt.cache().close();
        } else {
          caches.replace(bound.key(), bound.cache(), rebuilt.cache());
        }
      }
      run.events().cacheInvalidated(rebuilt.invalidation());
      return Rebuild.REBUILT;
    } catch (CacheUnavailableException failed) {
      synchronized (caches) {
        caches.remove(bound.key(), bound.cache());
      }
      Log.error("falha ao reconstruir o cache de " + bound.key().value(), failed);
      failed.discarded().ifPresent(run.events()::cacheInvalidated);
      return failed.discarded().isPresent() ? Rebuild.DISCARDED : Rebuild.NOTHING_RENAMED;
    } catch (RuntimeException failed) {
      Log.error("falha ao reconstruir o cache de " + bound.key().value(), failed);
      return Rebuild.DISCARDED;
    }
  }

  enum Rebuild {
    REBUILT,
    NOTHING_RENAMED,
    DISCARDED
  }

  void release(Run run) {
    synchronized (slot) {
      active.compareAndSet(run, null);
    }
  }

  public record CancelResult(boolean cancelled, Runnable afterResponse) {

    private static final Runnable NOTHING = () -> {};

    private static final CancelResult ALREADY_REQUESTED = new CancelResult(true, NOTHING);

    private static final CancelResult REFUSED = new CancelResult(false, NOTHING);

    public CancelResult {
      Objects.requireNonNull(afterResponse, "afterResponse");
    }

    public static CancelResult requested(Runnable cancelToken) {
      return new CancelResult(true, cancelToken);
    }

    public static CancelResult alreadyRequested() {
      return ALREADY_REQUESTED;
    }

    public static CancelResult refused() {
      return REFUSED;
    }
  }
}
