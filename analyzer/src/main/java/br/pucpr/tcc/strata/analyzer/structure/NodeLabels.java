package br.pucpr.tcc.strata.analyzer.structure;

public final class NodeLabels {

  private NodeLabels() {}

  public static String segmentAlignedPrefix(String first, String last) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static String commonProperPrefix(String segmentAlignedPrefix, boolean isAPackage) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static String packageLabel(String qualifiedName, String commonProperPrefix) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static String prefixLabel(String prefix, String commonProperPrefix) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static String typeLabel(String typeQualifiedName, String packageQualifiedName) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }

  public static String clamp(String label) {
    throw new UnsupportedOperationException("ainda não implementado: labels");
  }
}
