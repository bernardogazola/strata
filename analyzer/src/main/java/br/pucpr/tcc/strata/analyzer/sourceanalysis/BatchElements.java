package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.List;
import java.util.Objects;

public record BatchElements(
    List<PackageElement> packages, List<TypeElement> types, List<MethodElement> methods) {

  public BatchElements {
    packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    types = List.copyOf(Objects.requireNonNull(types, "types"));
    methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
  }

  public int size() {
    return packages.size() + types.size() + methods.size();
  }
}
