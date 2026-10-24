package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;

public final class NodeLabels {

  private static final char SEPARATOR = '.';

  private NodeLabels() {}

  public static String segmentAlignedPrefix(String first, String last) {
    final int common = Math.min(first.length(), last.length());
    int lastDot = 0;
    int index = 0;
    while (index < common && first.charAt(index) == last.charAt(index)) {
      if (first.charAt(index) == SEPARATOR) {
        lastDot = index;
      }
      index++;
    }
    final boolean endsASegment =
        endsSegmentAt(first, index) && endsSegmentAt(last, index) && index > 0;
    return first.substring(0, endsASegment ? index : lastDot);
  }

  public static String commonProperPrefix(String segmentAlignedPrefix, boolean isAPackage) {
    if (!isAPackage || segmentAlignedPrefix.isEmpty()) {
      return segmentAlignedPrefix;
    }
    return segmentAlignedPrefix.substring(
        0, Math.max(0, segmentAlignedPrefix.lastIndexOf(SEPARATOR)));
  }

  public static String packageLabel(String qualifiedName, String commonProperPrefix) {
    return clamp(withoutPrefix(qualifiedName, commonProperPrefix));
  }

  public static String prefixLabel(String prefix, String commonProperPrefix) {
    return clamp(withoutPrefix(prefix, commonProperPrefix));
  }

  public static String typeLabel(String typeQualifiedName, String packageQualifiedName) {
    return clamp(withoutPrefix(typeQualifiedName, packageQualifiedName));
  }

  public static String clamp(String label) {
    if (label.codePointCount(0, label.length()) <= AnalysisConstants.MAX_LABEL_CHARS) {
      return label;
    }
    return label.substring(0, label.offsetByCodePoints(0, AnalysisConstants.MAX_LABEL_CHARS));
  }

  private static String withoutPrefix(String name, String prefix) {
    if (prefix.isEmpty()
        || name.length() <= prefix.length()
        || !name.startsWith(prefix)
        || name.charAt(prefix.length()) != SEPARATOR) {
      return name;
    }
    return name.substring(prefix.length() + 1);
  }

  private static boolean endsSegmentAt(String name, int index) {
    return index == name.length() || name.charAt(index) == SEPARATOR;
  }
}
