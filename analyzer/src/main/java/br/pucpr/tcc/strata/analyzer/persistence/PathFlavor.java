package br.pucpr.tcc.strata.analyzer.persistence;

public enum PathFlavor {
  POSIX,
  WINDOWS;

  public static PathFlavor host() {
    final String osName = System.getProperty("os.name");
    return osName != null && osName.startsWith("Windows") ? WINDOWS : POSIX;
  }
}
