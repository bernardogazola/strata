package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceSelection {

  private static final Comparator<MethodRef> BY_METHOD_ID =
      Comparator.nullsFirst(Comparator.comparing(MethodRef::elementId));

  private static final Comparator<String> BY_FILE_PATH =
      Comparator.nullsFirst(CodeElementId::compareCodePoints);

  private static final Comparator<RelationshipEvidence> BY_CONTENT =
      Comparator.comparing(
              RelationshipEvidence::line, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
          .thenComparing(RelationshipEvidence::fromMethod, BY_METHOD_ID)
          .thenComparing(RelationshipEvidence::toMethod, BY_METHOD_ID)
          .thenComparing(RelationshipEvidence::resolution)
          .thenComparing(RelationshipEvidence::filePath, BY_FILE_PATH);

  private EvidenceSelection() {}

  public static List<RelationshipEvidence> select(
      Collection<RelationshipEvidence> stored, int limit) {
    Objects.requireNonNull(stored, "stored");
    if (limit < 1) {
      throw new IllegalArgumentException("o limite de evidências é pelo menos 1: " + limit);
    }
    final Map<DependencyType, List<RelationshipEvidence>> byType =
        new EnumMap<>(DependencyType.class);
    for (final RelationshipEvidence evidence : stored) {
      byType.computeIfAbsent(evidence.type(), type -> new ArrayList<>()).add(evidence);
    }
    byType.values().forEach(evidence -> evidence.sort(BY_CONTENT));
    final Map<DependencyType, Integer> taken = turns(byType, limit);
    final List<RelationshipEvidence> wire = new ArrayList<>();
    byType.forEach((type, evidence) -> wire.addAll(evidence.subList(0, taken.get(type))));
    return List.copyOf(wire);
  }

  private static Map<DependencyType, Integer> turns(
      Map<DependencyType, List<RelationshipEvidence>> byType, int limit) {
    final Map<DependencyType, Integer> taken = new EnumMap<>(DependencyType.class);
    byType.keySet().forEach(type -> taken.put(type, 0));
    int total = 0;
    boolean progressed = true;
    while (total < limit && progressed) {
      progressed = false;
      for (final Map.Entry<DependencyType, List<RelationshipEvidence>> entry : byType.entrySet()) {
        final int count = taken.get(entry.getKey());
        if (total < limit && count < entry.getValue().size()) {
          taken.put(entry.getKey(), count + 1);
          total++;
          progressed = true;
        }
      }
    }
    return taken;
  }
}
