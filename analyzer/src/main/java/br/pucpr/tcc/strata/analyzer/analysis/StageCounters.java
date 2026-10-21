package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record StageCounters(Map<String, Long> values) {

  private static final Pattern KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");

  private static final StageCounters NONE = new StageCounters(Map.of());

  private static final CanonicalJson JSON = new CanonicalJson();

  public StageCounters {
    Objects.requireNonNull(values, "values");
    final TreeMap<String, Long> sorted = new TreeMap<>();
    for (final Map.Entry<String, Long> entry : values.entrySet()) {
      final String key = entry.getKey();
      if (key == null || !KEY.matcher(key).matches()) {
        throw new IllegalArgumentException(
            "o nome do contador deve casar com " + KEY.pattern() + ": " + key);
      }
      final Long value = entry.getValue();
      if (value == null || value < 0) {
        throw new IllegalArgumentException("o contador " + key + " deve ser >= 0: " + value);
      }
      sorted.put(key, value);
    }
    values = Collections.unmodifiableMap(sorted);
  }

  public static StageCounters none() {
    return NONE;
  }

  public static StageCounters of(Map<String, Long> values) {
    return new StageCounters(values);
  }

  public String toJson() {
    return JSON.valueToString(values);
  }
}
