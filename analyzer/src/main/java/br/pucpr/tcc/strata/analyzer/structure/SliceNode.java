package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public record SliceNode(
    String id,
    GraphNodeKind kind,
    String label,
    long loc,
    int typeCount,
    int fanIn,
    int fanOut,
    double pagerank,
    ResolutionMix mix) {

  public static final String PREFIX_ID_PREFIX = "pfx:";

  public SliceNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(mix, "mix");
    if (!idMatchesKind(id, kind)) {
      throw new IllegalArgumentException("um nó " + kind + " com id alheio");
    }
    if (label.codePointCount(0, label.length()) > AnalysisConstants.MAX_LABEL_CHARS) {
      throw new IllegalArgumentException("um rótulo de nó acima do teto: " + id);
    }
    if (loc < 0 || fanIn < 0 || fanOut < 0) {
      throw new IllegalArgumentException("uma contagem negativa no nó " + id);
    }
    if (kind == GraphNodeKind.TYPE ? typeCount != 1 : typeCount < 1) {
      throw new IllegalArgumentException(
          "um nó de tipo conta a si mesmo, e qualquer outro nó >= 1: " + id);
    }
    if (!Double.isFinite(pagerank) || pagerank < 0d || pagerank > 1d) {
      throw new IllegalArgumentException("um pagerank fora de [0, 1] no nó " + id);
    }
  }

  private static boolean idMatchesKind(String id, GraphNodeKind kind) {
    if (kind == GraphNodeKind.PACKAGE_PREFIX) {
      return id.length() > PREFIX_ID_PREFIX.length() && id.startsWith(PREFIX_ID_PREFIX);
    }
    final ElementKind expected =
        kind == GraphNodeKind.PACKAGE ? ElementKind.PACKAGE : ElementKind.TYPE;
    try {
      return new CodeElementId(id).kind() == expected;
    } catch (IllegalArgumentException notAnElementId) {
      return false;
    }
  }
}
