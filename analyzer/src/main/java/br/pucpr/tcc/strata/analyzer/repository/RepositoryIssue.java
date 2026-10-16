package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.UntrustedText;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public record RepositoryIssue(Code code, Map<String, Object> params) {

  public enum Code {
    REPO_PATH_CHANGED,
    REPO_ROOT_UNREADABLE,
    REPO_NOT_A_GIT_REPOSITORY,
    REPO_IS_BARE,
    REPO_TOO_MANY_FILES,
    REPO_NO_JAVA_SOURCES,
    REPO_PATH_ESCAPES_ROOT,
    REPO_UNREADABLE,
    REPO_NO_HISTORY
  }

  public RepositoryIssue {
    Objects.requireNonNull(code, "code");
    params = Map.copyOf(Objects.requireNonNull(params, "params"));
  }

  public static RepositoryIssue pathChanged() {
    return new RepositoryIssue(Code.REPO_PATH_CHANGED, Map.of());
  }

  public static RepositoryIssue rootUnreadable() {
    return new RepositoryIssue(Code.REPO_ROOT_UNREADABLE, Map.of());
  }

  public static RepositoryIssue notAGitRepository() {
    return new RepositoryIssue(Code.REPO_NOT_A_GIT_REPOSITORY, Map.of());
  }

  public static RepositoryIssue isBare() {
    return new RepositoryIssue(Code.REPO_IS_BARE, Map.of());
  }

  public static RepositoryIssue tooManyFiles(int limit, int found) {
    return new RepositoryIssue(Code.REPO_TOO_MANY_FILES, Map.of("found", found, "limit", limit));
  }

  public static RepositoryIssue noJavaSources() {
    return new RepositoryIssue(Code.REPO_NO_JAVA_SOURCES, Map.of());
  }

  public static RepositoryIssue pathEscapesRoot(String relativePath) {
    return new RepositoryIssue(
        Code.REPO_PATH_ESCAPES_ROOT, Map.of("filePath", reportable(relativePath)));
  }

  public static RepositoryIssue unreadable(String relativePath) {
    return new RepositoryIssue(Code.REPO_UNREADABLE, Map.of("filePath", reportable(relativePath)));
  }

  public static RepositoryIssue noHistory() {
    return new RepositoryIssue(Code.REPO_NO_HISTORY, Map.of());
  }

  public static String reportable(String relativePath) {
    String candidate = Objects.requireNonNull(relativePath, "relativePath");
    while (candidate.getBytes(StandardCharsets.UTF_8).length > AnalysisConstants.MAX_PATH_BYTES) {
      final int lastSeparator = candidate.lastIndexOf('/');
      candidate = lastSeparator < 0 ? "" : candidate.substring(0, lastSeparator);
    }
    return UntrustedText.clamp(candidate, AnalysisConstants.MAX_PATH_BYTES);
  }
}
