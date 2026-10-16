package br.pucpr.tcc.strata.analyzer.bootstrap;

public final class PathEncodingGuard {

  private PathEncodingGuard() {}

  public static boolean refuses(String osName, String jnuEncoding) {
    if (osName != null && osName.startsWith("Windows")) {
      return false;
    }
    return !("UTF-8".equalsIgnoreCase(jnuEncoding) || "UTF8".equalsIgnoreCase(jnuEncoding));
  }
}
