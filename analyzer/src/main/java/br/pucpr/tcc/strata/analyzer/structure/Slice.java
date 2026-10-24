package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Slice(
    GraphLevel level,
    CodeElementId parentId,
    List<SliceNode> nodes,
    List<SliceEdge> edges,
    SliceTotals totals,
    boolean truncated,
    boolean aggregated,
    SliceBudget budget) {

  public Slice {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(totals, "totals");
    Objects.requireNonNull(budget, "budget");
    nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    requireParentMatchesLevel(level, parentId);
    if (aggregated && level != GraphLevel.PACKAGE) {
      throw new IllegalArgumentException("só o nível PACKAGE agrega: " + level);
    }
    if (nodes.size() > budget.nodes() || edges.size() > budget.edges()) {
      throw new IllegalArgumentException("um recorte acima do seu próprio orçamento");
    }
    if (nodes.size() > totals.nodes() || edges.size() > totals.edges()) {
      throw new IllegalArgumentException("um recorte maior que o seu nível");
    }
    final boolean belowTotals = nodes.size() < totals.nodes() || edges.size() < totals.edges();
    if (!aggregated && truncated != belowTotals) {
      throw new IllegalArgumentException(
          "truncated deve dizer se um orçamento cortou este recorte");
    }
    requireNodes(level, aggregated, nodes);
    requireEdges(nodes, edges);
  }

  private static void requireParentMatchesLevel(GraphLevel level, CodeElementId parentId) {
    switch (level) {
      case PACKAGE -> {
        if (parentId != null) {
          throw new IllegalArgumentException("o nível PACKAGE não tem pai");
        }
      }
      case TYPE -> {
        if (parentId == null || parentId.kind() != ElementKind.PACKAGE) {
          throw new IllegalArgumentException("o nível TYPE tem um pacote como pai");
        }
      }
      case TYPE_NEIGHBORHOOD -> Objects.requireNonNull(parentId, "parentId");
    }
  }

  private static void requireNodes(GraphLevel level, boolean aggregated, List<SliceNode> nodes) {
    SliceNode previous = null;
    for (final SliceNode node : nodes) {
      final boolean allowed =
          switch (node.kind()) {
            case PACKAGE -> level == GraphLevel.PACKAGE;
            case PACKAGE_PREFIX -> aggregated;
            case TYPE -> level != GraphLevel.PACKAGE;
          };
      if (!allowed) {
        throw new IllegalArgumentException("um nó " + node.kind() + " no nível " + level);
      }
      if (previous != null && CodeElementId.compareCodePoints(previous.id(), node.id()) >= 0) {
        throw new IllegalArgumentException("os nós vêm em ordem crescente de id, uma vez cada");
      }
      previous = node;
    }
  }

  private static void requireEdges(List<SliceNode> nodes, List<SliceEdge> edges) {
    final Set<String> ids = new HashSet<>();
    for (final SliceNode node : nodes) {
      ids.add(node.id());
    }
    SliceEdge previous = null;
    for (final SliceEdge edge : edges) {
      if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
        throw new IllegalArgumentException("uma aresta para um nó fora do recorte: " + edge.id());
      }
      if (previous != null && compareEndpoints(previous, edge) >= 0) {
        throw new IllegalArgumentException(
            "as arestas vêm em ordem crescente de (source, target), uma vez cada");
      }
      previous = edge;
    }
  }

  private static int compareEndpoints(SliceEdge left, SliceEdge right) {
    final int bySource = CodeElementId.compareCodePoints(left.source(), right.source());
    return bySource != 0
        ? bySource
        : CodeElementId.compareCodePoints(left.target(), right.target());
  }
}
