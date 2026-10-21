package br.pucpr.tcc.strata.analyzer.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record StagePlan(Map<AnalysisStage, StageExecutor> executors) {

  public static final Set<AnalysisStage> SPI_STAGES =
      Collections.unmodifiableSet(
          EnumSet.range(AnalysisStage.SOURCE_ANALYSIS, AnalysisStage.PERSISTENCE));

  public StagePlan {
    Objects.requireNonNull(executors, "executors");
    if (!executors.keySet().equals(SPI_STAGES)) {
      throw new IllegalArgumentException(
          "um plano mapeia exatamente os estágios " + SPI_STAGES + ": " + executors.keySet());
    }
    final EnumMap<AnalysisStage, StageExecutor> copy = new EnumMap<>(AnalysisStage.class);
    for (final Map.Entry<AnalysisStage, StageExecutor> entry : executors.entrySet()) {
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("nenhum executor para " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    executors = Collections.unmodifiableMap(copy);
  }

  public StageExecutor executor(AnalysisStage stage) {
    final StageExecutor executor = executors.get(Objects.requireNonNull(stage, "stage"));
    if (executor == null) {
      throw new IllegalArgumentException("não é um estágio da SPI: " + stage);
    }
    return executor;
  }

  public StagePlan with(AnalysisStage stage, StageExecutor executor) {
    final EnumMap<AnalysisStage, StageExecutor> copy = new EnumMap<>(executors);
    if (!SPI_STAGES.contains(Objects.requireNonNull(stage, "stage"))) {
      throw new IllegalArgumentException("não é um estágio da SPI: " + stage);
    }
    copy.put(stage, Objects.requireNonNull(executor, "executor"));
    return new StagePlan(copy);
  }
}
