package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PrefixRollup {

  private static final char SEPARATOR = '.';

  private PrefixRollup() {}

  public static Map<String, PackageGroup> groups(Set<String> packageNames, int maxGroups) {
    if (maxGroups < 1) {
      throw new IllegalArgumentException("a regra G pede um teto de pelo menos 1: " + maxGroups);
    }
    if (packageNames.isEmpty()) {
      return Map.of();
    }
    final Map<PackageGroup, List<String>> groups = start(List.copyOf(packageNames));
    final Set<PackageGroup> refused = new HashSet<>();
    for (PackageGroup candidate = largest(groups, refused);
        candidate != null;
        candidate = largest(groups, refused)) {
      final Map<PackageGroup, List<String>> children =
          normalized(children(candidate, groups.get(candidate)));
      if (groups.size() - 1 + children.size() > maxGroups) {
        refused.add(candidate);
      } else {
        groups.remove(candidate);
        groups.putAll(children);
      }
    }
    final Map<String, PackageGroup> byPackage = HashMap.newHashMap(packageNames.size());
    groups.forEach((group, members) -> members.forEach(member -> byPackage.put(member, group)));
    return Map.copyOf(byPackage);
  }

  private static Map<PackageGroup, List<String>> start(List<String> names) {
    String common = names.getFirst();
    for (final String name : names) {
      common = NodeLabels.segmentAlignedPrefix(common, name);
    }
    if (!common.isEmpty()) {
      return normalized(Map.of(PackageGroup.prefix(common), names));
    }
    return normalized(childrenOf("", names));
  }

  private static PackageGroup largest(
      Map<PackageGroup, List<String>> groups, Set<PackageGroup> refused) {
    PackageGroup best = null;
    int most = 0;
    for (final Map.Entry<PackageGroup, List<String>> entry : groups.entrySet()) {
      final PackageGroup group = entry.getKey();
      if (!group.isPrefix() || refused.contains(group)) {
        continue;
      }
      final int members = entry.getValue().size();
      if (best == null
          || members > most
          || (members == most && CodeElementId.compareCodePoints(group.name(), best.name()) < 0)) {
        best = group;
        most = members;
      }
    }
    return best;
  }

  private static Map<PackageGroup, List<String>> children(
      PackageGroup prefix, List<String> members) {
    return childrenOf(prefix.name(), members);
  }

  private static Map<PackageGroup, List<String>> childrenOf(String prefix, List<String> members) {
    final int next = prefix.isEmpty() ? 0 : prefix.length() + 1;
    final Map<PackageGroup, List<String>> children = new HashMap<>();
    for (final String member : members) {
      final PackageGroup child;
      if (member.length() == prefix.length()) {
        child = PackageGroup.exact(member);
      } else {
        final int end = member.indexOf(SEPARATOR, next);
        child = PackageGroup.prefix(end < 0 ? member : member.substring(0, end));
      }
      children.computeIfAbsent(child, key -> new ArrayList<>()).add(member);
    }
    return children;
  }

  private static Map<PackageGroup, List<String>> normalized(
      Map<PackageGroup, List<String>> groups) {
    final Map<PackageGroup, List<String>> normal = new HashMap<>();
    groups.forEach(
        (group, members) -> {
          PackageGroup current = group;
          while (current.isPrefix()) {
            if (members.size() == 1) {
              current = PackageGroup.exact(members.getFirst());
            } else {
              final Map<PackageGroup, List<String>> children = children(current, members);
              if (children.size() != 1) {
                break;
              }
              current = children.keySet().iterator().next();
            }
          }
          normal.put(current, members);
        });
    return normal;
  }
}
