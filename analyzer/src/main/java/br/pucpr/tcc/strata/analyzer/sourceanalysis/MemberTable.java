package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

final class MemberTable {

  static final int PRIVATE = 0;

  static final int PACKAGE = 1;

  static final int PROTECTED = 2;

  static final int PUBLIC = 3;

  static final int VISIBILITY = 3;

  static final int STATIC = 4;

  static final int INSTANCE = 8;

  static final MemberTable EMPTY = new MemberTable(new String[0], new byte[0]);

  private final String[] names;
  private final byte[] flags;

  private MemberTable(String[] names, byte[] flags) {
    this.names = names;
    this.flags = flags;
  }

  static MemberTable of(SortedMap<String, Byte> members) {
    if (members.isEmpty()) {
      return EMPTY;
    }
    final String[] names = new String[members.size()];
    final byte[] flags = new byte[members.size()];
    int at = 0;
    for (final Map.Entry<String, Byte> member : members.entrySet()) {
      names[at] = member.getKey();
      flags[at] = member.getValue();
      at++;
    }
    return new MemberTable(names, flags);
  }

  static byte merge(byte left, byte right) {
    final int visibility = Math.max(left & VISIBILITY, right & VISIBILITY);
    return (byte) (visibility | ((left | right) & ~VISIBILITY));
  }

  static void put(TreeMap<String, Byte> members, String name, byte memberFlags) {
    members.merge(name, memberFlags, MemberTable::merge);
  }

  int find(String name) {
    return Arrays.binarySearch(names, name);
  }

  byte flags(int at) {
    return flags[at];
  }

  int size() {
    return names.length;
  }

  String name(int at) {
    return names[at];
  }

  static int visibility(byte memberFlags) {
    return memberFlags & VISIBILITY;
  }

  static boolean isStatic(byte memberFlags) {
    return (memberFlags & STATIC) != 0;
  }
}
