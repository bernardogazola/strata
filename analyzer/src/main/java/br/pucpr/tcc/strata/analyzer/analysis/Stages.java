package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import java.util.EnumMap;

public final class Stages {

  private Stages() {}

  public static StagePlan production() {
    final EnumMap<AnalysisStage, StageExecutor> executors = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : StagePlan.SPI_STAGES) {
      executors.put(stage, SkippedStage.INSTANCE);
    }
    executors.put(AnalysisStage.SOURCE_ANALYSIS, new SourceAnalysisStage(new SpoonModelBuilder()));
    executors.put(
        AnalysisStage.STRUCTURAL_GRAPH, new StructuralGraphStage(ExternalPackages.load()));
    executors.put(AnalysisStage.PERSISTENCE, PersistenceStage.INSTANCE);
    return new StagePlan(executors);
  }
}
