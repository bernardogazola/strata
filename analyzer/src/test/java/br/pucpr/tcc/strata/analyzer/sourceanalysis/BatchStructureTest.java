package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Complexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BatchStructureTest {

  private static final CodeElementId TYPE = CodeElementId.ofType("a.Order");

  private static final CodeElementId METHOD = CodeElementId.ofMethod("a.Order", "run", List.of());

  private static final BatchElements ONE_PACKAGE =
      new BatchElements(
          List.of(new PackageElement(CodeElementId.ofPackage("a"), "a", "a")),
          List.of(),
          List.of());

  @Test
  void asMetricasContamNoTamanhoDoLote() {
    final List<CodeMetric> metrics =
        List.of(
            new CodeMetric(TYPE, 10, Complexity.of(List.of(2))),
            new CodeMetric(METHOD, 3, Complexity.of(List.of(2))));

    final BatchStructure batch = new BatchStructure(ONE_PACKAGE, BatchDependencies.NONE, metrics);

    assertThat(batch.size()).isEqualTo(3);
  }

  @Test
  void oLoteSemMetricaTemOTamanhoDeAntes() {
    final BatchStructure batch = new BatchStructure(ONE_PACKAGE, BatchDependencies.NONE);

    assertThat(batch.metrics()).isEmpty();
    assertThat(batch.size()).isEqualTo(ONE_PACKAGE.size());
  }

  @Test
  void oLoteCopiaAsMetricas() {
    final List<CodeMetric> metrics = new ArrayList<>();
    metrics.add(new CodeMetric(TYPE, 10, Complexity.NONE));

    final BatchStructure batch = new BatchStructure(ONE_PACKAGE, BatchDependencies.NONE, metrics);
    metrics.add(new CodeMetric(METHOD, 3, null));

    assertThat(batch.metrics()).hasSize(1);
  }

  @Test
  void soTipoEMetodoTemMetrica() {
    assertThat(new CodeMetric(METHOD, 0, null).complexity()).isNull();
    assertThatThrownBy(() -> new CodeMetric(CodeElementId.ofPackage("a"), 0, Complexity.NONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeMetric(CodeElementId.ofFile("A.java"), 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeMetric(TYPE, -1, Complexity.NONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeMetric(TYPE, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CodeMetric(METHOD, 1, Complexity.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
