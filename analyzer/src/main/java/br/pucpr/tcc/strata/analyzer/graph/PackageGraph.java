package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.List;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class PackageGraph {

  PackageGraph() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int packageCount() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public CodeElementId packageId(int pkg) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public List<PackageEdge> edges() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int internalCohesion(int pkg) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int bridgeSpan(int type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int externalFanIn(int type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public Graph<Integer, DefaultEdge> forward() {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
