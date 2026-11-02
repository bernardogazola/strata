package br.pucpr.tcc.strata.analyzer.structure;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class EdgeIds {

  public static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

  public static final long FNV_PRIME = 0x100000001b3L;

  private static final String ID_PREFIX = "e_";

  private static final int ID_HEX_DIGITS = 16;

  private static final String SEPARATOR = Character.toString(0);

  private EdgeIds() {}

  public static String of(String sourceId, String targetId) {
    final String pair = sourceId + SEPARATOR + targetId;
    return ID_PREFIX
        + String.format(Locale.ROOT, "%016x", fnv1a64(pair.getBytes(StandardCharsets.UTF_8)));
  }

  public static boolean isEdgeId(String id) {
    if (id.length() != ID_PREFIX.length() + ID_HEX_DIGITS || !id.startsWith(ID_PREFIX)) {
      return false;
    }
    for (int i = ID_PREFIX.length(); i < id.length(); i++) {
      final char digit = id.charAt(i);
      if (!(digit >= '0' && digit <= '9') && !(digit >= 'a' && digit <= 'f')) {
        return false;
      }
    }
    return true;
  }

  public static long fnv1a64(byte[] bytes) {
    long hash = FNV_OFFSET_BASIS;
    for (final byte value : bytes) {
      hash ^= value & 0xff;
      hash *= FNV_PRIME;
    }
    return hash;
  }
}
