package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.List;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class PackageGraph {

  private final CodeElementId[] packageIds;
  private final List<PackageEdge> edges;
  private final int[] internalCohesion;
  private final int[] bridgeSpan;
  private final int[] externalFanIn;
  private final Graph<Integer, DefaultEdge> forward;

  PackageGraph(
      CodeElementId[] packageIds,
      List<PackageEdge> edges,
      int[] internalCohesion,
      int[] bridgeSpan,
      int[] externalFanIn,
      Graph<Integer, DefaultEdge> forward) {
    this.packageIds = packageIds;
    this.edges = List.copyOf(edges);
    this.internalCohesion = internalCohesion;
    this.bridgeSpan = bridgeSpan;
    this.externalFanIn = externalFanIn;
    this.forward = forward;
  }

  public int packageCount() {
    return packageIds.length;
  }

  public CodeElementId packageId(int pkg) {
    return packageIds[pkg];
  }

  public List<PackageEdge> edges() {
    return edges;
  }

  public int internalCohesion(int pkg) {
    return internalCohesion[pkg];
  }

  public int bridgeSpan(int type) {
    return bridgeSpan[type];
  }

  public int externalFanIn(int type) {
    return externalFanIn[type];
  }

  public Graph<Integer, DefaultEdge> forward() {
    return forward;
  }
}
