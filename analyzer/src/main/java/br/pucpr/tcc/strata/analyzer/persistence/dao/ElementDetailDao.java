package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class ElementDetailDao {

  private ElementDetailDao() {}

  public static Optional<ElementDetailRow> element(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: a linha do elemento");
  }

  public static Optional<CodeMetricRow> codeMetric(
      Connection connection, long analysisPk, long elementPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: as métricas de código");
  }

  public static Optional<GraphMetricRow> graphMetric(
      Connection connection, long analysisPk, long elementPk) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: os fatos do grafo");
  }

  public static List<ElementMethodRow> methods(
      Connection connection, long analysisPk, long typePk, int limit) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: os métodos");
  }

  public static int methodCount(Connection connection, long analysisPk, long typePk)
      throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: o total de métodos");
  }
}
