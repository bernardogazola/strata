package br.pucpr.tcc.strata.analyzer.protocol;

import java.math.BigDecimal;

final class CanonicalNumbers {

  private static final int PLAIN_EXPONENT_MIN_EXCLUSIVE = -6;

  private static final int PLAIN_EXPONENT_MAX = 21;

  private CanonicalNumbers() {}

  static String toJson(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("JSON não tem NaN nem Infinity: " + value);
    }
    if (value == 0.0d) {
      return "0";
    }
    if (Math.abs(value) < Double.MIN_NORMAL) {
      throw new IllegalArgumentException(
          "um subnormal não tem forma mais curta em comum com o JSON.stringify: " + value);
    }
    final BigDecimal shortest = BigDecimal.valueOf(value).stripTrailingZeros();
    final String digits = shortest.unscaledValue().abs().toString();
    final int exponent = digits.length() - shortest.scale();
    final StringBuilder text = new StringBuilder(digits.length() + 8);
    if (value < 0.0d) {
      text.append('-');
    }
    if (exponent > PLAIN_EXPONENT_MIN_EXCLUSIVE && exponent <= PLAIN_EXPONENT_MAX) {
      appendPlain(text, digits, exponent);
    } else {
      appendExponential(text, digits, exponent);
    }
    return text.toString();
  }

  private static void appendPlain(StringBuilder text, String digits, int exponent) {
    final int k = digits.length();
    if (exponent >= k) {
      text.append(digits).repeat('0', exponent - k);
    } else if (exponent > 0) {
      text.append(digits, 0, exponent).append('.').append(digits, exponent, k);
    } else {
      text.append("0.").repeat('0', -exponent).append(digits);
    }
  }

  private static void appendExponential(StringBuilder text, String digits, int exponent) {
    text.append(digits.charAt(0));
    if (digits.length() > 1) {
      text.append('.').append(digits, 1, digits.length());
    }
    final int power = exponent - 1;
    text.append('e').append(power < 0 ? '-' : '+').append(Math.abs(power));
  }
}
