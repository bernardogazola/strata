package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageNameBounds;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public final class CommonProperPrefix {

  private CommonProperPrefix() {}

  public static String read(Connection connection, long analysisPk) throws SQLException {
    final Optional<PackageNameBounds> bounds =
        StructureDao.packageNameBounds(connection, analysisPk);
    if (bounds.isEmpty()) {
      return "";
    }
    final String common =
        NodeLabels.segmentAlignedPrefix(bounds.get().first(), bounds.get().last());
    return common.isEmpty()
        ? common
        : NodeLabels.commonProperPrefix(
            common, StructureDao.packageExists(connection, analysisPk, common));
  }
}
