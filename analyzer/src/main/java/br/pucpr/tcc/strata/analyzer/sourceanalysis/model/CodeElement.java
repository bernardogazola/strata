package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;

public sealed interface CodeElement
    permits PackageElement, TypeElement, MethodElement, FileElement {

  static String label(String name) {
    if (name.codePointCount(0, name.length()) <= AnalysisConstants.MAX_LABEL_CHARS) {
      return name;
    }
    return name.substring(0, name.offsetByCodePoints(0, AnalysisConstants.MAX_LABEL_CHARS));
  }

  CodeElementId id();

  String simpleName();

  String qualifiedName();
}
