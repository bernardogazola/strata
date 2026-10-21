package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public record RegistrySettings(
    PersistenceRoot persistenceRoot,
    FrameSink sink,
    String analyzerVersion,
    InstantSource clock,
    LongSupplier nanoClock,
    Supplier<AnalysisId> ids,
    StagePlan plan,
    int cpuPoolThreads,
    Duration runStopWait) {

  public RegistrySettings {
    Objects.requireNonNull(persistenceRoot, "persistenceRoot");
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(analyzerVersion, "analyzerVersion");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(nanoClock, "nanoClock");
    Objects.requireNonNull(ids, "ids");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(runStopWait, "runStopWait");
    if (cpuPoolThreads < 1 || cpuPoolThreads > AnalysisConstants.CPU_POOL_MAX_THREADS) {
      throw new IllegalArgumentException(
          "cpuPoolThreads deve estar em [1, "
              + AnalysisConstants.CPU_POOL_MAX_THREADS
              + "]: "
              + cpuPoolThreads);
    }
    if (runStopWait.isNegative() || runStopWait.isZero()) {
      throw new IllegalArgumentException("runStopWait deve ser positivo: " + runStopWait);
    }
  }

  public static RegistrySettings production(
      PersistenceRoot persistenceRoot, FrameSink sink, String analyzerVersion) {
    return new RegistrySettings(
        persistenceRoot,
        sink,
        analyzerVersion,
        InstantSource.system(),
        System::nanoTime,
        AnalysisIds.system(),
        Stages.production(),
        productionCpuPoolThreads(),
        Duration.ofMillis(AnalysisConstants.RUN_STOP_WAIT_MILLIS));
  }

  private static int productionCpuPoolThreads() {
    final int spare = Runtime.getRuntime().availableProcessors() - 1;
    return Math.max(1, Math.min(spare, AnalysisConstants.CPU_POOL_MAX_THREADS));
  }

  public RegistrySettings withClock(InstantSource wallClock) {
    return new RegistrySettings(
        persistenceRoot,
        sink,
        analyzerVersion,
        wallClock,
        nanoClock,
        ids,
        plan,
        cpuPoolThreads,
        runStopWait);
  }

  public RegistrySettings withNanoClock(LongSupplier monotonic) {
    return new RegistrySettings(
        persistenceRoot,
        sink,
        analyzerVersion,
        clock,
        monotonic,
        ids,
        plan,
        cpuPoolThreads,
        runStopWait);
  }

  public RegistrySettings withIds(Supplier<AnalysisId> minter) {
    return new RegistrySettings(
        persistenceRoot,
        sink,
        analyzerVersion,
        clock,
        nanoClock,
        minter,
        plan,
        cpuPoolThreads,
        runStopWait);
  }

  public RegistrySettings withPlan(StagePlan stages) {
    return new RegistrySettings(
        persistenceRoot,
        sink,
        analyzerVersion,
        clock,
        nanoClock,
        ids,
        stages,
        cpuPoolThreads,
        runStopWait);
  }

  public RegistrySettings withCpuPoolThreads(int threads) {
    return new RegistrySettings(
        persistenceRoot, sink, analyzerVersion, clock, nanoClock, ids, plan, threads, runStopWait);
  }

  public RegistrySettings withRunStopWait(Duration wait) {
    return new RegistrySettings(
        persistenceRoot, sink, analyzerVersion, clock, nanoClock, ids, plan, cpuPoolThreads, wait);
  }
}
