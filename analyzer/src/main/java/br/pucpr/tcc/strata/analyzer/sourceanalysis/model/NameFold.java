package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import java.text.Normalizer;
import java.util.Objects;

public final class NameFold {

  private NameFold() {}

  public static String fold(String name) {
    Objects.requireNonNull(name, "name");
    final String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
    final StringBuilder folded = new StringBuilder(decomposed.length());
    for (int index = 0; index < decomposed.length(); ) {
      final int codePoint = decomposed.codePointAt(index);
      index += Character.charCount(codePoint);
      if (!isDropped(codePoint)) {
        folded.appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)));
      }
    }
    return folded.toString();
  }

  private static boolean isDropped(int codePoint) {
    final int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK || type == Character.FORMAT;
  }
}
