package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record DependencyEntry(
    String edgeId,
    CodeElementId elementId,
    ElementKind kind,
    String label,
    String qualifiedName,
    CodeElementId packageId,
    boolean isExternal,
    boolean ambiguous,
    ResolutionQuality resolution,
    Map<DependencyType, Long> types,
    long weight) {

  public DependencyEntry {
    Objects.requireNonNull(edgeId, "edgeId");
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(types, "types");
    if (!EdgeIds.isEdgeId(edgeId)) {
      throw new IllegalArgumentException("não é um id de aresta: " + edgeId);
    }
    if (kind != elementId.kind() || (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE)) {
      throw new IllegalArgumentException(
          "uma ponta de dependência é PACKAGE ou TYPE: " + elementId);
    }
    if (label.codePointCount(0, label.length()) > AnalysisConstants.MAX_LABEL_CHARS) {
      throw new IllegalArgumentException("um rótulo acima do teto: " + elementId);
    }
    if (!fitsEnd(kind, isExternal, packageId, resolution, ambiguous)) {
      throw new IllegalArgumentException("um campo que não cabe na ponta: " + elementId);
    }
    if (types.isEmpty()) {
      throw new IllegalArgumentException("uma dependência tem pelo menos um tipo: " + edgeId);
    }
    for (final Map.Entry<DependencyType, Long> entry : types.entrySet()) {
      if (!entry.getKey().isTypeEdge() || entry.getValue() == null || entry.getValue() < 1) {
        throw new IllegalArgumentException("um tipo presente tem >= 1 ocorrência: " + entry);
      }
    }
    types = Collections.unmodifiableMap(new EnumMap<>(types));
    if (weight < 1) {
      throw new IllegalArgumentException("uma dependência pesa pelo menos 1: " + edgeId);
    }
  }

  private static boolean fitsEnd(
      ElementKind kind,
      boolean isExternal,
      CodeElementId packageId,
      ResolutionQuality resolution,
      boolean ambiguous) {
    if (ambiguous && !(isExternal && resolution == ResolutionQuality.UNRESOLVED_EXTERNAL)) {
      return false;
    }
    if (isExternal) {
      return kind == ElementKind.TYPE && packageId == null;
    }
    if (resolution == ResolutionQuality.UNRESOLVED_EXTERNAL) {
      return false;
    }
    return kind == ElementKind.PACKAGE
        ? packageId == null
        : packageId != null && packageId.kind() == ElementKind.PACKAGE;
  }
}
