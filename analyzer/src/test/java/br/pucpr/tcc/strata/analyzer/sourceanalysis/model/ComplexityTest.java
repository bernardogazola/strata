package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class ComplexityTest {

  private static final int PROPERTY_MAX_UNITS = 10_000;

  @Test
  void semUnidadeEhTudoZero() {
    assertThat(Complexity.of(List.of())).isSameAs(Complexity.NONE);
    assertThat(Complexity.NONE).isEqualTo(new Complexity(0, 0, 0, 0));
    assertThat(Complexity.NONE.mean()).isZero();
  }

  @Test
  void oPercentilEhOValorDoPostoMaisProximo() {
    assertThat(Complexity.of(List.of(5))).isEqualTo(new Complexity(5, 5, 5, 1));
    assertThat(Complexity.of(List.of(1, 1, 2, 3, 4, 4, 5))).isEqualTo(new Complexity(20, 5, 5, 7));
    assertThat(Complexity.of(List.of(1, 1, 2, 3, 4, 5, 5, 6, 7, 10)))
        .isEqualTo(new Complexity(44, 10, 7, 10));
    assertThat(Complexity.of(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 100)))
        .isEqualTo(new Complexity(109, 100, 1, 10));
    assertThat(Complexity.of(IntStream.rangeClosed(1, 11).boxed().toList()).p90()).isEqualTo(10);
  }

  @Test
  void aOrdemDaEntradaNaoImporta() {
    assertThat(Complexity.of(List.of(10, 7, 1, 6, 5, 5, 4, 3, 2, 1)))
        .isEqualTo(new Complexity(44, 10, 7, 10));
  }

  @Test
  void setentaUnidadesDaoOPosto63EmAritmeticaInteira() {
    final List<Integer> units = new ArrayList<>(Collections.nCopies(63, 1));
    units.addAll(Collections.nCopies(7, 2));

    assertThat(Complexity.of(units)).isEqualTo(new Complexity(77, 2, 1, 70));
  }

  @Test
  void oPostoEhOTetoExatoDe90nSobre100() {
    final List<Integer> ascending = IntStream.rangeClosed(1, PROPERTY_MAX_UNITS).boxed().toList();
    final BigInteger hundred = BigInteger.valueOf(100);
    for (int n = 1; n <= PROPERTY_MAX_UNITS; n++) {
      final BigInteger[] quotient = BigInteger.valueOf(90L * n).divideAndRemainder(hundred);
      final int rank =
          quotient[0].add(quotient[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE).intValue();

      final int p90 = Complexity.of(ascending.subList(0, n)).p90();

      assertThat(rank).isBetween(1, n);
      assertThat(p90).as("n = %d", n).isEqualTo(rank);
    }
  }

  @Test
  void aMediaEhDerivadaDaSoma() {
    assertThat(Complexity.of(List.of(1, 2)).mean()).isEqualTo(1.5);
  }

  @Test
  void umaUnidadeValePeloMenosUm() {
    assertThatThrownBy(() -> Complexity.of(List.of(1, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Complexity.of(List.of(Integer.MAX_VALUE, 1)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void recusaOQueNenhumMulticonjuntoDeUnidadesProduz() {
    assertThatThrownBy(() -> new Complexity(0, 0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(0, 0, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(2, 0, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(3, 4, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(2, 1, 1, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(7, 3, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(4, 3, 0, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Complexity(4, 3, 4, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
