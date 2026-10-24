package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record SliceEdge(
    String id,
    String source,
    String target,
    Map<DependencyType, Long> types,
    long weight,
    ResolutionQuality resolution) {

  private static final String ID_PREFIX = "e_";
  private static final int ID_HEX_DIGITS = 16;

  public SliceEdge {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(types, "types");
    Objects.requireNonNull(resolution, "resolution");
    if (!isEdgeId(id)) {
      throw new IllegalArgumentException("não é um id de aresta: " + id);
    }
    if (source.equals(target)) {
      throw new IllegalArgumentException("uma aresta do recorte liga dois nós: " + source);
    }
    if (types.isEmpty()) {
      throw new IllegalArgumentException("uma aresta tem pelo menos um tipo de dependência: " + id);
    }
    for (final Map.Entry<DependencyType, Long> entry : types.entrySet()) {
      if (!entry.getKey().isTypeEdge() || entry.getValue() == null || entry.getValue() < 1) {
        throw new IllegalArgumentException("um tipo presente tem >= 1 ocorrência: " + entry);
      }
    }
    types = Collections.unmodifiableMap(new EnumMap<>(types));
    if (weight < 1) {
      throw new IllegalArgumentException("uma aresta pesa pelo menos 1: " + id);
    }
    if (resolution == ResolutionQuality.UNRESOLVED_EXTERNAL) {
      throw new IllegalArgumentException(
          "uma aresta do recorte nunca é UNRESOLVED_EXTERNAL: " + id);
    }
  }

  private static boolean isEdgeId(String id) {
    if (id.length() != ID_PREFIX.length() + ID_HEX_DIGITS || !id.startsWith(ID_PREFIX)) {
      return false;
    }
    for (int i = ID_PREFIX.length(); i < id.length(); i++) {
      final char digit = id.charAt(i);
      if (!(digit >= '0' && digit <= '9') && !(digit >= 'a' && digit <= 'f')) {
        return false;
      }
    }
    return true;
  }
}
