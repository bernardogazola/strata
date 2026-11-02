package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.List;
import java.util.Objects;

public record ElementDetail(
    CodeElementId elementId,
    ElementKind kind,
    TypeKind typeKind,
    String label,
    String qualifiedName,
    CodeElementId packageId,
    String filePath,
    SourceLines lines,
    List<String> modifiers,
    boolean isTest,
    ElementMetrics metrics,
    GraphFacts graph,
    List<MethodSummary> methods,
    int methodTotal) {

  public ElementDetail {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(graph, "graph");
    modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
    methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    if (kind != elementId.kind() || (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE)) {
      throw new IllegalArgumentException("um elemento descrito é PACKAGE ou TYPE: " + elementId);
    }
    if (!fitsKind(kind, typeKind, packageId, filePath, lines)
        || (kind == ElementKind.PACKAGE) != (metrics.ccP90() == null)) {
      throw new IllegalArgumentException("um campo que não cabe no tipo de elemento: " + elementId);
    }
    if (NodeLabels.exceedsCeiling(label)) {
      throw new IllegalArgumentException("um rótulo acima do teto: " + elementId);
    }
    if (modifiers.stream().anyMatch(String::isEmpty)) {
      throw new IllegalArgumentException("um modificador vazio: " + elementId);
    }
    if (methodTotal < 0
        || methods.size() != Math.min(methodTotal, AnalysisConstants.ELEMENT_METHODS_MAX)
        || (kind == ElementKind.PACKAGE && methodTotal != 0)) {
      throw new IllegalArgumentException("methods é min(methodTotal, o limite): " + elementId);
    }
  }

  private static boolean fitsKind(
      ElementKind kind,
      TypeKind typeKind,
      CodeElementId packageId,
      String filePath,
      SourceLines lines) {
    if (lines != null && !lines.available()) {
      return false;
    }
    if (kind == ElementKind.PACKAGE) {
      return typeKind == null && packageId == null && filePath == null && lines == null;
    }
    return typeKind != null
        && packageId != null
        && packageId.kind() == ElementKind.PACKAGE
        && filePath != null;
  }
}
