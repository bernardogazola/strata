package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CanonicalNumbersTest {

  private static final String ORACLE = "/protocol/js-number-forms.tsv";

  private static final Pattern ROW_COUNT =
      Pattern.compile("^# Gerado com node .*, (\\d+) linhas\\.$");

  private static final List<String> SOURCES =
      List.of("fixed", "pow10", "ulp", "pagerank", "bits", "decimal", "short", "plain");

  private static final double OLD_RANGE_MIN = 1e-6;
  private static final double OLD_RANGE_MAX_EXCLUSIVE = 1e21;

  private record OracleRow(long bits, String json, String source) {
    double value() {
      return Double.longBitsToDouble(bits);
    }

    String describe() {
      return Long.toHexString(bits) + " (" + source + ") esperado " + json;
    }
  }

  private static List<OracleRow> oracle() throws IOException {
    final List<OracleRow> rows = new ArrayList<>();
    int declared = -1;
    try (InputStream in = CanonicalNumbersTest.class.getResourceAsStream(ORACLE)) {
      assertThat(in).as(ORACLE).isNotNull();
      final BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.startsWith("#")) {
          final Matcher count = ROW_COUNT.matcher(line);
          if (count.matches()) {
            declared = Integer.parseInt(count.group(1));
          }
          continue;
        }
        final String[] fields = line.split("\t", -1);
        assertThat(fields).as(line).hasSize(3);
        rows.add(new OracleRow(Long.parseUnsignedLong(fields[0], 16), fields[1], fields[2]));
      }
    }
    assertThat(rows).as("as linhas que o cabeçalho declara").hasSize(declared);
    return rows;
  }

  private static String before(double value) {
    return value == 0.0d ? "0" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static boolean inOldRange(double value) {
    final double magnitude = Math.abs(value);
    return value == 0.0d || (magnitude >= OLD_RANGE_MIN && magnitude < OLD_RANGE_MAX_EXCLUSIVE);
  }

  @Test
  void todaLinhaDoOraculoSaiComOsBytesDoJsonStringify() throws IOException {
    final List<OracleRow> rows = oracle();
    final Map<String, Integer> bySource = new TreeMap<>();
    final List<String> mismatches = new ArrayList<>();

    for (final OracleRow row : rows) {
      bySource.merge(row.source(), 1, Integer::sum);
      final String emitted = CanonicalNumbers.toJson(row.value());
      if (!emitted.equals(row.json())) {
        mismatches.add(row.describe() + ", emitido " + emitted);
      }
    }

    assertThat(bySource.keySet()).containsExactlyInAnyOrderElementsOf(SOURCES);
    assertThat(mismatches).as("linhas em que o Java diverge do Node").isEmpty();
  }

  @Test
  void aFaixaAntigaSaiComOsMesmosBytesDeAntes() throws IOException {
    final List<OracleRow> rows = oracle();
    final List<String> changed = new ArrayList<>();
    int inside = 0;
    int outside = 0;

    for (final OracleRow row : rows) {
      final boolean exponential = row.json().contains("e");
      if (inOldRange(row.value())) {
        inside++;
        assertThat(exponential).as(row.describe()).isFalse();
        final String emitted = CanonicalNumbers.toJson(row.value());
        if (!emitted.equals(before(row.value()))) {
          changed.add(row.describe() + ", antes " + before(row.value()) + ", agora " + emitted);
        }
      } else {
        outside++;
        assertThat(exponential).as(row.describe()).isTrue();
      }
    }

    assertThat(changed).as("valores da faixa antiga cuja grafia mudou").isEmpty();
    assertThat(inside).isGreaterThan(2_000);
    assertThat(outside).isGreaterThan(3_000);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0.0                     | 0",
        "-0.0                    | 0",
        "1e-6                    | 0.000001",
        "9.999999999999999e-7    | 0.000001",
        "7.37e-6                 | 0.00000737",
        "3.81e-6                 | 0.00000381",
        "1e-7                    | 1e-7",
        "1.5e-7                  | 1.5e-7",
        "-2.5e-10                | -2.5e-10",
        "1e-10                   | 1e-10",
        "2.2250738585072014E-308 | 2.2250738585072014e-308",
        "0.1                     | 0.1",
        "88.0                    | 88",
        "123456789012345680000.0 | 123456789012345680000",
        "9.999999999999999e20    | 999999999999999900000",
        "1e21                    | 1e+21",
        "1e22                    | 1e+22",
        "-1.5e300                | -1.5e+300",
        "1.7976931348623157E308  | 1.7976931348623157e+308"
      })
  void osPassosDeEcma262(double value, String expected) {
    assertThat(CanonicalNumbers.toJson(value)).isEqualTo(expected);
  }

  @Test
  void umTercoEZeroVirgulaUmMaisZeroVirgulaDois() {
    assertThat(CanonicalNumbers.toJson(1.0d / 3.0d)).isEqualTo("0.3333333333333333");
    assertThat(CanonicalNumbers.toJson(0.1d + 0.2d)).isEqualTo("0.30000000000000004");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void naoHaNaNNemInfinityEmJson(double value) {
    assertThatThrownBy(() -> CanonicalNumbers.toJson(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oSubnormalERecusado() {
    final double largestSubnormal = Math.nextDown(Double.MIN_NORMAL);
    final List<Double> subnormals =
        List.of(
            Double.MIN_VALUE,
            -Double.MIN_VALUE,
            1e-310,
            -1e-320,
            largestSubnormal,
            -largestSubnormal);

    for (final double value : subnormals) {
      assertThat(Math.abs(value)).isPositive().isLessThan(Double.MIN_NORMAL);
      assertThatThrownBy(() -> CanonicalNumbers.toJson(value))
          .as(Double.toString(value))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("subnormal");
    }
    assertThat(CanonicalNumbers.toJson(Double.MIN_NORMAL)).isEqualTo("2.2250738585072014e-308");
  }
}
