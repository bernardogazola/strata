package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.SQLException;

public final class PragmaRefusedException extends SQLException {

  private static final long serialVersionUID = 1L;

  public PragmaRefusedException(String pragma, String expected, String actual) {
    super("PRAGMA " + pragma + " devolveu " + actual + ", esperado " + expected);
  }
}
