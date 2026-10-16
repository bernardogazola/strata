package br.pucpr.tcc.strata.analyzer.sourceanalysis;

@FunctionalInterface
public interface BatchVisitor {

  void visit(ModeledBatch batch);
}
