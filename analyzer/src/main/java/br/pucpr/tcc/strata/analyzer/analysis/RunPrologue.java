package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot.StageSnapshot;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisCounters;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CacheMetadata;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CacheMetadataDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryRecord;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageRecord;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.repository.HeadState;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositorySnapshot;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.RootCommits;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

final class RunPrologue {

  private static final long NANOS_PER_MILLI = 1_000_000L;

  private final AnalysisRegistry registry;
  private final RegistrySettings settings;
  private final ProgressThrottle throttle;

  RunPrologue(AnalysisRegistry registry, RegistrySettings settings, ProgressThrottle throttle) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
  }

  private record Inspected(
      RepositoryValidator validator, RepositorySnapshot snapshot, String rootCommitId) {}

  private record Discovered(ValidatedRepository repository, List<SourceRoot> selected) {}

  private record Step<T>(T value, StageCounters counters) {}

  @FunctionalInterface
  private interface PrologueStep<T> {
    Step<T> run();
  }

  RunInputs run(Run run) throws CacheUnavailableException, SQLException {
    final CancellationToken token = run.token();
    token.throwIfCancelled();
    final Inspected inspected = stage(run, AnalysisStage.REPOSITORY_VALIDATION, () -> inspect(run));
    token.throwIfCancelled();
    final Discovered discovered =
        stage(run, AnalysisStage.LAYOUT_DISCOVERY, () -> discover(run, inspected));
    token.throwIfCancelled();
    bind(run, inspected, discovered);
    return new RunInputs(
        discovered.repository(),
        inspected.rootCommitId(),
        run.configuration(),
        discovered.selected());
  }

  private <T> T stage(Run run, AnalysisStage stage, PrologueStep<T> step) {
    final LongSupplier nanos = settings.nanoClock();
    final long startedAt = settings.clock().millis();
    final long startNanos = nanos.getAsLong();
    run.stageRunning(stage, startedAt);
    final Step<T> done;
    try {
      done = step.run();
    } catch (RuntimeException interrupted) {
      run.stageEnded(
          stage,
          AnalysisRunner.interruptedStatus(run, run.token()),
          startedAt,
          millisSince(nanos, startNanos),
          StageCounters.none());
      throw interrupted;
    }
    run.stageEnded(
        stage, StageStatus.COMPLETED, startedAt, millisSince(nanos, startNanos), done.counters());
    return done.value();
  }

  private Step<Inspected> inspect(Run run) {
    final ProgressReporter progress =
        new ProgressReporter(
            run.id(), AnalysisStage.REPOSITORY_VALIDATION, throttle, run.events()::progress);
    progress.report(ProgressUnit.JAVA_FILES, 0, null);
    final RepositoryValidator validator = new RepositoryValidator();
    final RepositoryOutcome<RepositorySnapshot> outcome =
        validator.inspect(
            run.claimedRoot(),
            run.token().asSupplier(),
            seen -> progress.report(ProgressUnit.JAVA_FILES, seen, null));
    return switch (outcome) {
      case RepositoryOutcome.Rejected<RepositorySnapshot>(RepositoryIssue reason) ->
          throw new StageFailedException(ErrorCodes.repository(reason));
      case RepositoryOutcome.Accepted<RepositorySnapshot>(
              RepositorySnapshot snapshot,
              List<RepositoryIssue> warnings) ->
          inspected(run, validator, snapshot, warnings, progress);
    };
  }

  private Step<Inspected> inspected(
      Run run,
      RepositoryValidator validator,
      RepositorySnapshot snapshot,
      List<RepositoryIssue> warnings,
      ProgressReporter progress) {
    final String rootCommitId =
        RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), run.token().asSupplier())
            .orElse(null);
    run.headCommitRead(snapshot.head().commitId());
    final long javaFiles = snapshot.inventory().javaSources().size();
    progress.finish(ProgressUnit.JAVA_FILES, javaFiles, javaFiles);
    for (final RepositoryIssue warning : warnings) {
      run.events().warn(ErrorCodes.repository(warning));
    }
    return new Step<>(
        new Inspected(validator, snapshot, rootCommitId),
        StageCounters.of(
            Map.of(
                "files", (long) snapshot.inventory().totalFileCount(),
                "javaFiles", javaFiles,
                "warnings", (long) warnings.size())));
  }

  private static Step<Discovered> discover(Run run, Inspected inspected) {
    final ValidatedRepository repository =
        switch (inspected.validator().discoverLayout(inspected.snapshot())) {
          case RepositoryOutcome.Rejected<ValidatedRepository>(RepositoryIssue reason) ->
              throw new StageFailedException(ErrorCodes.repository(reason));
          case RepositoryOutcome.Accepted<ValidatedRepository>(
                  ValidatedRepository validated,
                  var _) ->
              validated;
        };
    final List<SourceRoot> selected =
        SourceSelection.select(repository.layout(), run.configuration());
    final List<SourceRoot> roots = repository.layout().sourceRoots();
    return new Step<>(
        new Discovered(repository, selected),
        StageCounters.of(
            Map.of(
                "sourceRoots", (long) roots.size(),
                "mainRoots", count(roots, SourceRootKind.MAIN),
                "generatedRoots", count(roots, SourceRootKind.GENERATED),
                "testRoots", count(roots, SourceRootKind.TEST),
                "selectedRoots", (long) selected.size())));
  }

  private static long count(List<SourceRoot> roots, SourceRootKind kind) {
    return roots.stream().filter(root -> root.kind() == kind).count();
  }

  private void bind(Run run, Inspected inspected, Discovered discovered)
      throws CacheUnavailableException, SQLException {
    final LongSupplier nanos = settings.nanoClock();
    final long bindStart = nanos.getAsLong();
    final RepositoryIdentity identity =
        RepositoryIdentity.of(run.repositoryPath(), PathFlavor.host(), inspected.rootCommitId());
    final RepositoryCache cache =
        registry.cacheFor(identity, inspected.snapshot().root(), run.events());
    final Run.Binding attached = new Run.Binding(cache, identity.key(), 0);
    run.bind(attached);
    final HeadState head = inspected.snapshot().head();
    final int total = discovered.selected().stream().mapToInt(SourceRoot::javaFileCount).sum();
    final Completeness bound = new Completeness(total, 0, 0, head.shallow(), null);
    final long analysisPk =
        cache
            .connections()
            .write(
                connection -> writeBind(connection, run, identity, inspected, discovered, bound));
    run.bind(attached.withRow(analysisPk));
    run.boundCompleteness(bound);
    Log.info(
        "análise "
            + run.id().value()
            + " vinculada a repositoryKey="
            + identity.key().value()
            + " bindMillis="
            + millisSince(nanos, bindStart));
  }

  private long writeBind(
      Connection connection,
      Run run,
      RepositoryIdentity identity,
      Inspected inspected,
      Discovered discovered,
      Completeness bound)
      throws SQLException {
    final RepositorySnapshot snapshot = inspected.snapshot();
    final HeadState head = snapshot.head();
    final long now = settings.clock().millis();
    RepositoryDao.upsert(
        connection,
        new RepositoryRecord(
            identity.key().value(),
            snapshot.displayName(),
            identity.pathFingerprint().value(),
            identity.rootCommitId(),
            head.branch(),
            head.shallow(),
            discovered.repository().layout().buildSystem(),
            now,
            now));
    final CacheMetadata metadata =
        CacheMetadataDao.find(connection)
            .orElseThrow(() -> new SQLException("cache_metadata não tem linha"));
    final AnalysisConfiguration configuration = run.configuration();
    final RunSnapshot current = run.snapshot();
    final long analysisPk =
        AnalysisDao.insertRunning(
            connection,
            new NewAnalysis(
                run.id().value(),
                current.startedAtMillis(),
                head.commitId(),
                settings.analyzerVersion(),
                metadata.schemaVersion(),
                AnalysisConstants.PROTOCOL_VERSION,
                ConfigHash.of(configuration),
                ConfigHash.canonicalJson(configuration),
                null,
                configuration.includeTests(),
                configuration.historyScope(),
                configuration.historyScopeCommits()));
    for (final AnalysisStage stage : AnalysisStage.values()) {
      StageDao.upsert(connection, analysisPk, row(stage, current.stages().get(stage)));
    }
    final AnalysisCounters counters = bound.toCounters();
    AnalysisDao.updateCounters(connection, analysisPk, counters);
    return analysisPk;
  }

  static StageRecord row(AnalysisStage stage, StageSnapshot snapshot) {
    return new StageRecord(
        stage,
        snapshot.status(),
        snapshot.startedAtMillis(),
        snapshot.durationMs(),
        snapshot.counters().toJson());
  }

  static long millisSince(LongSupplier nanos, long startNanos) {
    return Math.max(0, (nanos.getAsLong() - startNanos) / NANOS_PER_MILLI);
  }
}
