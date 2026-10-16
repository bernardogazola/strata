package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class SpoonModelBuilder {

  public static final int COMPLIANCE_LEVEL = 25;

  public SpoonModelBuilder() {}

  public SourceAnalysis analyze(
      ValidatedRepository repository,
      List<SourceRoot> roots,
      BatchVisitor visitor,
      BooleanSupplier cancelled) {
    throw new UnsupportedOperationException("SpoonModelBuilder.analyze: ainda não implementado");
  }
}
