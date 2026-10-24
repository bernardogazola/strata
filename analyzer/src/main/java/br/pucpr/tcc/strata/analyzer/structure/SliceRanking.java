package br.pucpr.tcc.strata.analyzer.structure;

import java.util.List;
import java.util.Objects;

public final class SliceRanking {

  private SliceRanking() {}

  public static Selection select(List<SliceNode> nodes, List<SliceEdge> edges, SliceBudget budget) {
    throw new UnsupportedOperationException("ainda não implementado: ranking e corte");
  }

  public record Selection(List<SliceNode> nodes, List<SliceEdge> edges, boolean truncated) {

    public Selection {
      nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
      edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }
  }
}
