package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record MethodRef(
    CodeElementId elementId, String signature, CodeElementId ownerId, String ownerLabel) {

  public MethodRef {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(ownerLabel, "ownerLabel");
    if (elementId.kind() != ElementKind.METHOD || ownerId.kind() != ElementKind.TYPE) {
      throw new IllegalArgumentException("um método de um tipo: " + elementId + " de " + ownerId);
    }
    if (exceedsLabel(signature) || exceedsLabel(ownerLabel)) {
      throw new IllegalArgumentException("um texto acima do teto: " + elementId);
    }
  }

  private static boolean exceedsLabel(String text) {
    return text.codePointCount(0, text.length()) > AnalysisConstants.MAX_LABEL_CHARS;
  }
}
