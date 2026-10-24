package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.ANNOTATION_REFERENCE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.MODULE_DEPENDENCY;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TypeOccurrencesTest {

  private static final String ID = "e_0123456789abcdef";

  @Test
  void recusaOMapaVazioEAContagemAbaixoDeUm() {
    final Map<DependencyType, Long> nula = new HashMap<>();
    nula.put(CALLS, null);
    for (final Map<DependencyType, Long> tipos :
        List.of(
            Map.<DependencyType, Long>of(),
            Map.of(CALLS, 0L),
            Map.of(CALLS, -1L),
            nula,
            Map.of(MODULE_DEPENDENCY, 1L))) {
      assertThatThrownBy(() -> TypeOccurrences.checked(tipos, ID))
          .as("%s", tipos)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void copiaEmOrdemDeDeclaracaoEImutavel() {
    final Map<DependencyType, Long> entrada = new LinkedHashMap<>();
    entrada.put(ANNOTATION_REFERENCE, 3L);
    entrada.put(CALLS, 2L);
    entrada.put(IMPORTS, 1L);

    final Map<DependencyType, Long> copia = TypeOccurrences.checked(entrada, ID);
    entrada.put(CALLS, 99L);

    assertThat(copia.keySet()).containsExactly(IMPORTS, CALLS, ANNOTATION_REFERENCE);
    assertThat(copia).containsEntry(CALLS, 2L);
    assertThatThrownBy(() -> copia.put(IMPORTS, 5L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void osTresRecordsUsamOValidador() {
    final CodeElementId a = new CodeElementId("type:app.A");
    final CodeElementId b = new CodeElementId("type:app.B");
    for (final Map<DependencyType, Long> tipos :
        List.of(Map.<DependencyType, Long>of(), Map.of(CALLS, 0L))) {
      assertThatThrownBy(() -> new SliceEdge(ID, a.value(), b.value(), tipos, 1, RESOLVED))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new PairEdge(ID, a, b, tipos, 1, RESOLVED, false))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new DependencyEntry(
                      ID,
                      b,
                      b.kind(),
                      "B",
                      "app.B",
                      new CodeElementId("pkg:app"),
                      false,
                      false,
                      RESOLVED,
                      tipos,
                      1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
