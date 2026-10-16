package br.pucpr.tcc.strata.analyzer.bootstrap;

public final class UntrustedText {

  private UntrustedText() {}

  public static String clamp(String value, int maxUtf16Units) {
    final StringBuilder clean = new StringBuilder(Math.min(value.length(), maxUtf16Units));
    for (int index = 0; index < value.length(); ) {
      final int codePoint = value.codePointAt(index);
      index += Character.charCount(codePoint);
      if (codePoint < 0x20 && codePoint != '\t') {
        continue;
      }
      if (clean.length() + Character.charCount(codePoint) > maxUtf16Units) {
        break;
      }
      clean.appendCodePoint(codePoint);
    }
    return clean.toString();
  }
}
