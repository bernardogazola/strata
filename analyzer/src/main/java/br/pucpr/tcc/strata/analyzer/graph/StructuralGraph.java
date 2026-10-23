package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Map;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class StructuralGraph {

  StructuralGraph() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int typeCount() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public CodeElementId typeId(int type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int packageCount() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public CodeElementId packageId(int pkg) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int packageOf(int type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int edgeCount() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int edgeSource(int edge) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int edgeTarget(int edge) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public ResolutionQuality edgeResolution(int edge) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public Map<DependencyType, Integer> edgeOccurrences(int edge) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public int fanOutExternal(int type) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public long selfPairs() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public long externalPairs() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public long denylistedPairs() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public Graph<Integer, DefaultEdge> forward() {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
