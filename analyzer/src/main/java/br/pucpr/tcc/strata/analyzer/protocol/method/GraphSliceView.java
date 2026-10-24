package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.GraphLevel;
import br.pucpr.tcc.strata.analyzer.structure.GraphNodeKind;
import br.pucpr.tcc.strata.analyzer.structure.Slice;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GraphSliceView(
    boolean aggregated,
    Budget budget,
    List<Edge> edges,
    GraphLevel level,
    List<Node> nodes,
    String parentId,
    Totals totals,
    boolean truncated) {

  public GraphSliceView {
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(totals, "totals");
    edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
  }

  public static GraphSliceView of(Slice slice) {
    throw new UnsupportedOperationException("ainda não implementado: graph.slice (no fio)");
  }

  public record Budget(int edges, int nodes) {}

  public record Totals(int edges, int nodes) {}

  public record Node(
      int fanIn,
      int fanOut,
      String id,
      GraphNodeKind kind,
      String label,
      long loc,
      double pagerank,
      Mix resolutionMix,
      int typeCount) {}

  public record Edge(
      String id,
      ResolutionQuality resolution,
      String source,
      String target,
      Map<DependencyType, Long> types,
      long weight) {}

  public record Mix(
      @JsonProperty("INFERRED") long inferred,
      @JsonProperty("RESOLVED") long resolved,
      @JsonProperty("UNRESOLVED_EXTERNAL") long unresolvedExternal) {}
}
