package br.pucpr.tcc.strata.analyzer.structure;

public final class EdgeIds {

  public static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

  public static final long FNV_PRIME = 0x100000001b3L;

  private EdgeIds() {}

  public static String of(String sourceId, String targetId) {
    throw new UnsupportedOperationException("ainda não implementado: o id da aresta");
  }

  public static long fnv1a64(byte[] bytes) {
    throw new UnsupportedOperationException("ainda não implementado: o id da aresta");
  }
}
