package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SliceRanking {

  private static final Comparator<SliceNode> WIRE_NODE_ORDER =
      Comparator.comparing(SliceNode::id, CodeElementId::compareCodePoints);

  private static final Comparator<SliceNode> NODE_RANK =
      Comparator.comparingDouble(SliceNode::pagerank).reversed().thenComparing(WIRE_NODE_ORDER);

  private static final Comparator<SliceEdge> WIRE_EDGE_ORDER =
      Comparator.comparing(SliceEdge::source, CodeElementId::compareCodePoints)
          .thenComparing(SliceEdge::target, CodeElementId::compareCodePoints);

  private static final Comparator<SliceEdge> EDGE_RANK =
      Comparator.comparingLong(SliceEdge::weight).reversed().thenComparing(WIRE_EDGE_ORDER);

  private SliceRanking() {}

  public static Selection select(List<SliceNode> nodes, List<SliceEdge> edges, SliceBudget budget) {
    Objects.requireNonNull(budget, "budget");
    final Set<String> ids = nodeIds(nodes);
    requireEdges(ids, edges);

    final List<SliceNode> keptNodes =
        nodes.size() > budget.nodes()
            ? nodes.stream().sorted(NODE_RANK).limit(budget.nodes()).toList()
            : nodes;
    final Set<String> keptIds = keptNodes.size() < nodes.size() ? nodeIds(keptNodes) : ids;
    final List<SliceEdge> between =
        edges.stream()
            .filter(edge -> keptIds.contains(edge.source()) && keptIds.contains(edge.target()))
            .toList();
    final List<SliceEdge> keptEdges =
        between.size() > budget.edges()
            ? between.stream().sorted(EDGE_RANK).limit(budget.edges()).toList()
            : between;

    final boolean truncated = keptNodes.size() < nodes.size() || keptEdges.size() < edges.size();
    return new Selection(
        keptNodes.stream().sorted(WIRE_NODE_ORDER).toList(),
        keptEdges.stream().sorted(WIRE_EDGE_ORDER).toList(),
        truncated);
  }

  private static Set<String> nodeIds(List<SliceNode> nodes) {
    final Set<String> ids = HashSet.newHashSet(nodes.size());
    for (final SliceNode node : nodes) {
      if (!ids.add(node.id())) {
        throw new IllegalArgumentException("um id de nó aparece duas vezes: " + node.id());
      }
    }
    return ids;
  }

  private static void requireEdges(Set<String> ids, List<SliceEdge> edges) {
    final Set<Pair> pairs = HashSet.newHashSet(edges.size());
    for (final SliceEdge edge : edges) {
      if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
        throw new IllegalArgumentException("uma aresta para um nó fora do nível: " + edge.id());
      }
      if (!pairs.add(new Pair(edge.source(), edge.target()))) {
        throw new IllegalArgumentException("um par de aresta aparece duas vezes: " + edge.id());
      }
    }
  }

  private record Pair(String source, String target) {}

  public record Selection(List<SliceNode> nodes, List<SliceEdge> edges, boolean truncated) {

    public Selection {
      nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
      edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }
  }
}
