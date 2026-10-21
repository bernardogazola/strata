package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record Complexity(int sum, int max, int p90, int units) {

  public static final Complexity NONE = new Complexity(0, 0, 0, 0);

  private static final int PERCENTILE = 90;

  private static final int PERCENT = 100;

  public Complexity {
    if (units < 0) {
      throw new IllegalArgumentException("uma contagem de unidades negativa: " + units);
    }
    if (units == 0) {
      if (sum != 0 || max != 0 || p90 != 0) {
        throw new IllegalArgumentException(
            "nenhuma unidade e um valor: " + sum + "/" + max + "/" + p90);
      }
    } else if (max < 1
        || max > sum
        || sum < units
        || sum > (long) max * units
        || p90 < 1
        || p90 > max) {
      throw new IllegalArgumentException(
          "não é o agregado de "
              + units
              + " unidade(s): sum "
              + sum
              + ", max "
              + max
              + ", p90 "
              + p90);
    }
  }

  public static Complexity of(List<Integer> unitValues) {
    Objects.requireNonNull(unitValues, "unitValues");
    if (unitValues.isEmpty()) {
      return NONE;
    }
    final int[] ascending = new int[unitValues.size()];
    int sum = 0;
    for (int i = 0; i < ascending.length; i++) {
      final int value = Objects.requireNonNull(unitValues.get(i), "unit value");
      if (value < 1) {
        throw new IllegalArgumentException("uma unidade de CC vale pelo menos 1: " + value);
      }
      ascending[i] = value;
      sum = Math.addExact(sum, value);
    }
    Arrays.sort(ascending);
    return new Complexity(
        sum, ascending[ascending.length - 1], pctValue(ascending, PERCENTILE), ascending.length);
  }

  public double mean() {
    return units == 0 ? 0 : (double) sum / units;
  }

  private static int pctValue(int[] ascending, int percentile) {
    final long rank = Math.ceilDiv((long) percentile * ascending.length, PERCENT);
    return ascending[(int) rank - 1];
  }
}
