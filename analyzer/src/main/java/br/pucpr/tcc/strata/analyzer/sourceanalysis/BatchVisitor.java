package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.List;

@FunctionalInterface
public interface BatchVisitor {

  default void begin(List<SourceFile> files) {}

  void visit(ModeledBatch batch);

  default void end(SourceAnalysis analysis) {}
}
