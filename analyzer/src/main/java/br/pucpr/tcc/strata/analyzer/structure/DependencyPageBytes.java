package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class DependencyPageBytes {

  private static final int ESCAPED_CHAR_BYTES = 6;

  private static final int FIRST_UNESCAPED = 0x20;

  private static final int MAX_ONE_BYTE = 0x7F;

  private static final int MAX_TWO_BYTES = 0x7FF;

  private static final int MAX_THREE_BYTES = 0xFFFF;

  public static final int ROW_OVERHEAD_BYTES =
      ("{\"ambiguous\":false,\"edgeId\":\"\",\"elementId\":\"\",\"isExternal\":false,\"kind\":\""
                  + longestName(ElementKind.values())
                  + "\",\"label\":\"\",\"packageId\":null,\"qualifiedName\":\"\",\"resolution\":\""
                  + longestName(ResolutionQuality.values())
                  + "\",\"types\":{},\"weight\":"
                  + Long.MAX_VALUE
                  + "},")
              .length()
          + largestTypesMapBytes();

  public static final int PAGE_OVERHEAD_BYTES =
      ("{\"items\":[],\"nextCursor\":\""
              + "x".repeat(DependencyCursor.ENCODED_CHARS)
              + "\",\"totals\":{\"in\":"
              + Integer.MAX_VALUE
              + ",\"out\":"
              + Integer.MAX_VALUE
              + ",\"outExternal\":"
              + Integer.MAX_VALUE
              + "}}")
          .length();

  private DependencyPageBytes() {}

  public static long estimate(DependencyEntry entry) {
    Objects.requireNonNull(entry, "entry");
    long bytes = ROW_OVERHEAD_BYTES;
    bytes += stringBytes(entry.edgeId());
    bytes += stringBytes(entry.elementId().value());
    bytes += stringBytes(entry.label());
    bytes += stringBytes(entry.qualifiedName());
    if (entry.packageId() != null) {
      bytes += stringBytes(entry.packageId().value());
    }
    return bytes;
  }

  public static int fit(List<DependencyEntry> candidates, int limit, long budgetBytes) {
    Objects.requireNonNull(candidates, "candidates");
    if (limit < 1) {
      throw new IllegalArgumentException("uma página tem pelo menos uma linha: " + limit);
    }
    final int most = Math.min(limit, candidates.size());
    long running = PAGE_OVERHEAD_BYTES;
    int count = 0;
    while (count < most) {
      final long bytes = estimate(candidates.get(count));
      if (count > 0 && running + bytes > budgetBytes) {
        break;
      }
      running += bytes;
      count++;
    }
    return count;
  }

  private static long stringBytes(String text) {
    long bytes = 0;
    int index = 0;
    while (index < text.length()) {
      final int codePoint = text.codePointAt(index);
      bytes += codePointBytes(codePoint);
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  private static int codePointBytes(int codePoint) {
    final boolean escaped =
        codePoint < FIRST_UNESCAPED
            || codePoint == '"'
            || codePoint == '\\'
            || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    if (escaped) {
      return ESCAPED_CHAR_BYTES;
    }
    if (codePoint <= MAX_ONE_BYTE) {
      return 1;
    }
    if (codePoint <= MAX_TWO_BYTES) {
      return 2;
    }
    return codePoint <= MAX_THREE_BYTES ? 3 : 4;
  }

  private static String longestName(Enum<?>[] values) {
    return Arrays.stream(values)
        .map(Enum::name)
        .reduce("", (a, b) -> b.length() > a.length() ? b : a);
  }

  private static int largestTypesMapBytes() {
    int bytes = 0;
    for (final DependencyType type : DependencyType.values()) {
      if (type.isTypeEdge()) {
        bytes += ("\"" + type.name() + "\":" + Long.MAX_VALUE + ",").length();
      }
    }
    return bytes;
  }
}
