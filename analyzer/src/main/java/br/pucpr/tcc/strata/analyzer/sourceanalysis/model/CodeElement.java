package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;

public sealed interface CodeElement
    permits PackageElement, TypeElement, MethodElement, FileElement {

  CodeElementId id();

  String simpleName();

  String qualifiedName();
}
