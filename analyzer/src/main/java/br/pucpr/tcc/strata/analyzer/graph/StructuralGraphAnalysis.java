package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;

public final class StructuralGraphAnalysis {

  private StructuralGraphAnalysis() {}

  public static StructuralGraphResult compute(
      GraphInput input, ExternalPackages packages, BooleanSupplier cancelled) {
    return compute(
        input,
        packages,
        cancelled,
        AnalysisConstants.PAGERANK_MAX_ITER,
        AnalysisConstants.BETWEENNESS_MAX_NODES);
  }

  static StructuralGraphResult compute(
      GraphInput input,
      ExternalPackages packages,
      BooleanSupplier cancelled,
      int pageRankMaxIterations,
      int betweennessMaxNodes) {
    Objects.requireNonNull(cancelled, "cancelled");
    requireNotCancelled(cancelled);
    final StructuralGraph graph = StructuralGraphBuilder.build(input, packages, cancelled);
    if (graph.typeCount() == 0) {
      return StructuralGraphResult.EMPTY;
    }
    requireNotCancelled(cancelled);
    final PackageGraph packageGraph = PackageProjection.project(graph, cancelled);
    final Measures typeMeasures = measure(graph.forward(), pageRankMaxIterations, cancelled);
    final Measures packageMeasures =
        measure(packageGraph.forward(), pageRankMaxIterations, cancelled);
    requireNotCancelled(cancelled);
    final int[] components = GraphAlgorithms.weakComponents(packageGraph.forward());
    requireNotCancelled(cancelled);
    final Optional<double[]> betweenness =
        GraphAlgorithms.betweenness(packageGraph.forward(), betweennessMaxNodes);
    requireNotCancelled(cancelled);
    return new StructuralGraphResult(
        typeMetrics(graph, packageGraph, typeMeasures),
        packageMetrics(packageGraph, packageMeasures, components, betweenness),
        packageGraph.edges(),
        counters(graph, packageGraph, typeMeasures, packageMeasures, betweenness.isEmpty()));
  }

  private static Measures measure(
      Graph<Integer, DefaultEdge> forward, int pageRankMaxIterations, BooleanSupplier cancelled) {
    requireNotCancelled(cancelled);
    final PageRankVector pagerank = GraphAlgorithms.pageRank(forward, pageRankMaxIterations);
    requireNotCancelled(cancelled);
    final PageRankVector pagerankOut =
        GraphAlgorithms.pageRank(new EdgeReversedGraph<>(forward), pageRankMaxIterations);
    requireNotCancelled(cancelled);
    final CycleAssignment cycles = GraphAlgorithms.stronglyConnected(forward);
    return new Measures(pagerank, pagerankOut, cycles);
  }

  private static List<TypeMetrics> typeMetrics(
      StructuralGraph graph, PackageGraph packageGraph, Measures measures) {
    final int typeCount = graph.typeCount();
    final int[] fanIn = new int[typeCount];
    final int[] fanOut = new int[typeCount];
    final int[] fanInResolved = new int[typeCount];
    final int[] fanOutResolved = new int[typeCount];
    for (int edge = 0; edge < graph.edgeCount(); edge++) {
      final int source = graph.edgeSource(edge);
      final int target = graph.edgeTarget(edge);
      fanOut[source]++;
      fanIn[target]++;
      if (graph.edgeResolution(edge) == ResolutionQuality.RESOLVED) {
        fanOutResolved[source]++;
        fanInResolved[target]++;
      }
    }
    final List<TypeMetrics> metrics = new ArrayList<>(typeCount);
    for (int type = 0; type < typeCount; type++) {
      metrics.add(
          new TypeMetrics(
              graph.typeId(type),
              fanIn[type],
              fanOut[type],
              fanInResolved[type],
              fanOutResolved[type],
              graph.fanOutExternal(type),
              packageGraph.externalFanIn(type),
              measures.pagerank().score(type),
              measures.pagerankOut().score(type),
              measures.cycles().cycleOf(type).orElse(null),
              packageGraph.bridgeSpan(type)));
    }
    return metrics;
  }

  private static List<PackageMetrics> packageMetrics(
      PackageGraph packageGraph,
      Measures measures,
      int[] components,
      Optional<double[]> betweenness) {
    final Graph<Integer, DefaultEdge> forward = packageGraph.forward();
    final double[] intermediation = betweenness.orElse(null);
    final List<PackageMetrics> metrics = new ArrayList<>(packageGraph.packageCount());
    for (int pkg = 0; pkg < packageGraph.packageCount(); pkg++) {
      metrics.add(
          new PackageMetrics(
              packageGraph.packageId(pkg),
              forward.inDegreeOf(pkg),
              forward.outDegreeOf(pkg),
              measures.pagerank().score(pkg),
              measures.pagerankOut().score(pkg),
              measures.cycles().cycleOf(pkg).orElse(null),
              components[pkg],
              packageGraph.internalCohesion(pkg),
              intermediation == null ? null : Double.valueOf(intermediation[pkg])));
    }
    return metrics;
  }

  private static GraphCounters counters(
      StructuralGraph graph,
      PackageGraph packageGraph,
      Measures types,
      Measures packages,
      boolean betweennessSkipped) {
    return new GraphCounters(
        graph.typeCount(),
        graph.edgeCount(),
        graph.selfPairs(),
        graph.externalPairs(),
        graph.denylistedPairs(),
        packageGraph.packageCount(),
        packageGraph.edges().size(),
        types.cycles().cycleCount(),
        types.cycles().verticesInCycles(),
        packages.cycles().cycleCount(),
        packages.cycles().verticesInCycles(),
        types.unconverged() + packages.unconverged(),
        betweennessSkipped ? 1 : 0);
  }

  private static void requireNotCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException("grafo estrutural cancelado");
    }
  }

  private record Measures(
      PageRankVector pagerank, PageRankVector pagerankOut, CycleAssignment cycles) {

    private int unconverged() {
      return (pagerank.converged() ? 0 : 1) + (pagerankOut.converged() ? 0 : 1);
    }
  }
}
