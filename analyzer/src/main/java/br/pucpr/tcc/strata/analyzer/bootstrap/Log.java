package br.pucpr.tcc.strata.analyzer.bootstrap;

public final class Log {

  private Log() {}

  public static void info(String message) {
    write("INFO", message);
  }

  public static void warn(String message) {
    write("WARN", message);
  }

  public static void error(String message) {
    write("ERROR", message);
  }

  public static void error(String message, Throwable cause) {
    write("ERROR", message + ": " + cause.getClass().getName() + ": " + cause.getMessage());
  }

  private static void write(String level, String message) {
    System.err.println("[" + level + "] analyzer: " + message);
  }
}
