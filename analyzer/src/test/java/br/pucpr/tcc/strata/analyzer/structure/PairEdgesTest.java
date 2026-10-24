package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PairEdgesTest {

  private static final CodeElementId A = new CodeElementId("type:app.A");
  private static final CodeElementId B = new CodeElementId("type:Foo");

  private static PairRow linha(
      CodeElementId origem,
      CodeElementId destino,
      DependencyType tipo,
      ResolutionQuality qualidade,
      int ocorrencias,
      boolean ambigua) {
    return new PairRow(new Dependency(origem, destino, tipo, qualidade, ocorrencias), ambigua);
  }

  @Test
  void oLacoSaiEOAmbiguoEhOOuDasLinhas() {
    final List<PairRow> linhas =
        List.of(
            linha(A, A, CALLS, RESOLVED, 5, false),
            linha(A, B, CALLS, UNRESOLVED_EXTERNAL, 1, true),
            linha(A, B, PARAMETER_TYPE, UNRESOLVED_EXTERNAL, 1, false),
            linha(B, A, FIELD_TYPE, RESOLVED, 2, false));

    assertThat(PairEdges.aggregate(linhas))
        .containsExactly(
            new PairEdge(
                EdgeIds.of(B.value(), A.value()), B, A, Map.of(FIELD_TYPE, 2L), 6, RESOLVED, false),
            new PairEdge(
                EdgeIds.of(A.value(), B.value()),
                A,
                B,
                Map.of(CALLS, 1L, PARAMETER_TYPE, 1L),
                3,
                UNRESOLVED_EXTERNAL,
                true));
  }

  @Test
  void soLacosDaoNenhumPar() {
    final List<PairRow> linhas =
        List.of(
            linha(A, A, CALLS, RESOLVED, 1, false),
            linha(B, B, CALLS, UNRESOLVED_EXTERNAL, 2, true));

    assertThat(PairEdges.aggregate(linhas)).isEmpty();
  }
}
