package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.MethodRef;
import br.pucpr.tcc.strata.analyzer.structure.RelationshipEvidence;
import java.util.List;
import java.util.Objects;

public record EvidenceResultView(List<Item> evidence) {

  public EvidenceResultView {
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
  }

  public static EvidenceResultView of(List<RelationshipEvidence> evidence) {
    return new EvidenceResultView(evidence.stream().map(EvidenceResultView::item).toList());
  }

  private static Item item(RelationshipEvidence evidence) {
    return new Item(
        evidence.filePath(),
        method(evidence.fromMethod()),
        evidence.line(),
        evidence.resolution(),
        method(evidence.toMethod()),
        evidence.type());
  }

  private static MethodReference method(MethodRef method) {
    if (method == null) {
      return null;
    }
    return new MethodReference(
        method.elementId().value(),
        method.ownerId().value(),
        method.ownerLabel(),
        method.signature());
  }

  public record Item(
      String filePath,
      MethodReference fromMethod,
      Integer line,
      ResolutionQuality resolution,
      MethodReference toMethod,
      DependencyType type) {}

  public record MethodReference(
      String elementId, String ownerId, String ownerLabel, String signature) {}
}
