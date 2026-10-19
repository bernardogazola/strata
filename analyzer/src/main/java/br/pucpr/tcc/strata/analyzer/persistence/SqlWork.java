package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlWork<T> {

  T run(Connection connection) throws SQLException;
}
