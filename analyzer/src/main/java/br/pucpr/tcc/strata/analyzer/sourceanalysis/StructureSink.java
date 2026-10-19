package br.pucpr.tcc.strata.analyzer.sourceanalysis;

public interface StructureSink extends ElementSink {

  void accept(BatchStructure batch);
}
