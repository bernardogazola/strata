package br.pucpr.tcc.strata.analyzer.protocol;

import java.math.BigDecimal;

final class CanonicalNumbers {

  private static final double MIN_PLAIN = 1e-6;
  private static final double MAX_PLAIN = 1e21;

  private CanonicalNumbers() {}

  static String toJson(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("JSON não tem NaN nem Infinity: " + value);
    }
    if (value == 0.0d) {
      return "0";
    }
    final double magnitude = Math.abs(value);
    if (magnitude < MIN_PLAIN || magnitude >= MAX_PLAIN) {
      throw new IllegalArgumentException(
          "valor sem forma decimal idêntica ao JSON.stringify: " + value);
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
