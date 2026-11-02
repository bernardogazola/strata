package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import java.util.Objects;

public record MethodSummary(
    CodeElementId elementId, String signature, Integer cc, SourceLines lines) {

  public MethodSummary {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(signature, "signature");
    if (elementId.kind() != ElementKind.METHOD) {
      throw new IllegalArgumentException("não é um método: " + elementId);
    }
    if (NodeLabels.exceedsCeiling(signature)) {
      throw new IllegalArgumentException("uma assinatura acima do teto: " + elementId);
    }
    if (cc != null && cc < 1) {
      throw new IllegalArgumentException("o CC de uma unidade é >= 1, ou null: " + elementId);
    }
    if (lines != null && !lines.available()) {
      throw new IllegalArgumentException(
          "nenhuma posição ausente é null, nunca UNAVAILABLE: " + elementId);
    }
  }
}
