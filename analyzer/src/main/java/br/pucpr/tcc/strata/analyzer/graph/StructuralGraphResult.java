package br.pucpr.tcc.strata.analyzer.graph;

import java.util.List;
import java.util.Objects;

public record StructuralGraphResult(
    List<TypeMetrics> types,
    List<PackageMetrics> packages,
    List<PackageEdge> packageEdges,
    GraphCounters counters) {

  public static final StructuralGraphResult EMPTY =
      new StructuralGraphResult(List.of(), List.of(), List.of(), GraphCounters.EMPTY);

  public StructuralGraphResult {
    types = List.copyOf(Objects.requireNonNull(types, "types"));
    packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
    packageEdges = List.copyOf(Objects.requireNonNull(packageEdges, "packageEdges"));
    Objects.requireNonNull(counters, "counters");
  }
}
