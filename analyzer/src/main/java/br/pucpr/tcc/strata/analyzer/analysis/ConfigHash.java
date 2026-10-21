package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class ConfigHash {

  private static final CanonicalJson JSON = new CanonicalJson();

  private ConfigHash() {}

  public static String of(AnalysisConfiguration configuration) {
    final MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("todo JDK traz SHA-256", impossible);
    }
    return HexFormat.of()
        .formatHex(sha256.digest(canonicalJson(configuration).getBytes(StandardCharsets.UTF_8)));
  }

  public static String canonicalJson(AnalysisConfiguration configuration) {
    Objects.requireNonNull(configuration, "configuration");
    return JSON.valueToString(
        new HashedFields(
            configuration.historyScope(),
            configuration.historyScopeCommits(),
            configuration.includeTests(),
            configuration.sourceRootIds()));
  }

  private record HashedFields(
      HistoryScope historyScope,
      Integer historyScopeCommits,
      boolean includeTests,
      List<String> sourceRootIds) {}
}
