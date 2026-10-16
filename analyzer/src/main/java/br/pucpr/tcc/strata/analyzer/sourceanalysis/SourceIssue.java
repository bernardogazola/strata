package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;

public record SourceIssue(Code code, Map<String, Object> params) {

  public enum Code {
    SOURCE_PARSE_FAILED,
    SOURCE_FILE_TOO_LARGE
  }

  public SourceIssue {
    Objects.requireNonNull(code, "code");
    params = Map.copyOf(Objects.requireNonNull(params, "params"));
  }

  public static SourceIssue parseFailed(String relativePath) {
    return new SourceIssue(Code.SOURCE_PARSE_FAILED, Map.of("filePath", wirePath(relativePath)));
  }

  public static SourceIssue fileTooLarge(String relativePath, int limitBytes) {
    return new SourceIssue(
        Code.SOURCE_FILE_TOO_LARGE,
        Map.of("filePath", wirePath(relativePath), "limitBytes", limitBytes));
  }

  private static String wirePath(String relativePath) {
    final String nfc =
        Normalizer.normalize(
            Objects.requireNonNull(relativePath, "relativePath"), Normalizer.Form.NFC);
    return RepositoryIssue.reportable(nfc);
  }
}
