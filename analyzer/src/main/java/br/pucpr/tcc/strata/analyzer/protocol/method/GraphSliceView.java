package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.GraphLevel;
import br.pucpr.tcc.strata.analyzer.structure.GraphNodeKind;
import br.pucpr.tcc.strata.analyzer.structure.Slice;
import br.pucpr.tcc.strata.analyzer.structure.SliceEdge;
import br.pucpr.tcc.strata.analyzer.structure.SliceNode;
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
    return new GraphSliceView(
        slice.aggregated(),
        new Budget(slice.budget().edges(), slice.budget().nodes()),
        slice.edges().stream().map(GraphSliceView::edge).toList(),
        slice.level(),
        slice.nodes().stream().map(GraphSliceView::node).toList(),
        slice.parentId() == null ? null : slice.parentId().value(),
        new Totals(slice.totals().edges(), slice.totals().nodes()),
        slice.truncated());
  }

  private static Node node(SliceNode node) {
    final ResolutionMix mix = node.mix();
    return new Node(
        node.fanIn(),
        node.fanOut(),
        node.id(),
        node.kind(),
        node.label(),
        node.loc(),
        node.pagerank(),
        new Mix(mix.inferred(), mix.resolved(), mix.unresolvedExternal()),
        node.typeCount());
  }

  private static Edge edge(SliceEdge edge) {
    return new Edge(
        edge.id(), edge.resolution(), edge.source(), edge.target(), edge.types(), edge.weight());
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
