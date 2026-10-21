package br.pucpr.tcc.strata.analyzer.analysis;

import java.io.IOException;
import java.sql.SQLException;

@FunctionalInterface
public interface StageExecutor {

  StageOutcome execute(StageContext context) throws SQLException, IOException;
}
