package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;

public record SourceIssue(Code code, Map<String, Object> params) {

  public enum Code {
    SOURCE_PARSE_FAILED,
    SOURCE_FILE_TOO_LARGE,
    SOURCE_DUPLICATE_ELEMENT,
    SOURCE_ELEMENT_ID_TOO_LONG
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

  public static SourceIssue duplicateElement(String relativePath, String firstRelativePath) {
    return new SourceIssue(
        Code.SOURCE_DUPLICATE_ELEMENT,
        Map.of("filePath", wirePath(relativePath), "firstFilePath", wirePath(firstRelativePath)));
  }

  public static SourceIssue elementIdTooLong(String relativePath, int limitBytes) {
    return new SourceIssue(
        Code.SOURCE_ELEMENT_ID_TOO_LONG,
        Map.of("filePath", wirePath(relativePath), "limitBytes", limitBytes));
  }

  private static String wirePath(String relativePath) {
    final String nfc =
        Normalizer.normalize(
            Objects.requireNonNull(relativePath, "relativePath"), Normalizer.Form.NFC);
    return RepositoryIssue.reportable(nfc);
  }
}
