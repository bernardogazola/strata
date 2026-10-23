package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import java.sql.SQLException;
import java.util.Objects;

public final class StructuralGraphStage implements StageExecutor {

  private final ExternalPackages packages;

  public StructuralGraphStage(ExternalPackages packages) {
    this.packages = Objects.requireNonNull(packages, "packages");
  }

  @Override
  public StageOutcome execute(StageContext context) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
