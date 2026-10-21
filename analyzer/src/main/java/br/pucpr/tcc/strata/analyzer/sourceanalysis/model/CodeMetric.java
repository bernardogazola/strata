package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record CodeMetric(CodeElementId element, int loc, Complexity complexity) {

  public CodeMetric {
    Objects.requireNonNull(element, "element");
    final ElementKind kind = element.kind();
    if (kind != ElementKind.TYPE && kind != ElementKind.METHOD) {
      throw new IllegalArgumentException("só um TYPE ou um METHOD tem CodeMetric: " + element);
    }
    if (loc < 0) {
      throw new IllegalArgumentException("um loc negativo: " + loc + " para " + element);
    }
    if (complexity == null && kind == ElementKind.TYPE) {
      throw new IllegalArgumentException("um TYPE sempre tem complexidade: " + element);
    }
    if (kind == ElementKind.METHOD && complexity != null && complexity.units() == 0) {
      throw new IllegalArgumentException(
          "um METHOD sem unidade de CC não tem complexidade: " + element);
    }
  }
}
