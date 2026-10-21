package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.SQLException;

public final class TestWriteFailures {

  private TestWriteFailures() {}

  public static ElementWriter.WriteFailedException writeFailed(SQLException cause) {
    return new ElementWriter.WriteFailedException(cause);
  }
}
