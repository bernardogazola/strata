package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import java.util.List;
import java.util.Objects;

public record SourceFile(
    String relativePath,
    SourceRootId rootId,
    SourceFileStatus status,
    ParseFailureCause cause,
    Declarations declarations,
    Integer loc) {

  public SourceFile {
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(rootId, "rootId");
    Objects.requireNonNull(status, "status");
    if ((status == SourceFileStatus.PARSE_FAILED) != (cause != null)) {
      throw new IllegalArgumentException(
          "uma causa existe se e somente se o arquivo falhou: " + status + " / " + cause);
    }
    if (loc != null && loc < 0) {
      throw new IllegalArgumentException("um loc negativo: " + loc + " para " + relativePath);
    }
    final boolean neverDecoded =
        status == SourceFileStatus.SKIPPED_TOO_LARGE || cause == ParseFailureCause.UNREADABLE;
    if (neverDecoded && loc != null) {
      throw new IllegalArgumentException(
          "um arquivo cujo texto nunca foi decodificado não tem loc: " + status + " / " + cause);
    }
  }

  public SourceFile(
      String relativePath,
      SourceRootId rootId,
      SourceFileStatus status,
      ParseFailureCause cause,
      Declarations declarations) {
    this(relativePath, rootId, status, cause, declarations, null);
  }

  public record Declarations(String packageName, List<String> topLevelTypes) {

    public Declarations {
      Objects.requireNonNull(packageName, "packageName");
      topLevelTypes = List.copyOf(Objects.requireNonNull(topLevelTypes, "topLevelTypes"));
    }
  }
}
