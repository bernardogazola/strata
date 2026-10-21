package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CacheMaintenanceDao;
import java.sql.SQLException;

public enum PersistenceStage implements StageExecutor {
  INSTANCE;

  @Override
  public StageOutcome execute(StageContext context) throws SQLException {
    final CacheMaintenanceDao.WalCheckpoint checkpoint =
        context.connections().withWriter(CacheMaintenanceDao::checkpointPassive);
    if (checkpoint.busy()) {
      Log.info(
          "análise "
              + context.analysisId().value()
              + ": o checkpoint PASSIVE deixou "
              + (checkpoint.logFrames() - checkpoint.checkpointedFrames())
              + " frame(s) do WAL para o fechamento");
    }
    return StageOutcome.completed(StageCounters.none());
  }
}
