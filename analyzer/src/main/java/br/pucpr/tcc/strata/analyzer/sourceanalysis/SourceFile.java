package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import java.util.List;
import java.util.Objects;

public record SourceFile(
    String relativePath,
    SourceRootId rootId,
    SourceFileStatus status,
    ParseFailureCause cause,
    Declarations declarations) {

  public SourceFile {
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(rootId, "rootId");
    Objects.requireNonNull(status, "status");
    if ((status == SourceFileStatus.PARSE_FAILED) != (cause != null)) {
      throw new IllegalArgumentException(
          "uma causa existe se e somente se o arquivo falhou: " + status + " / " + cause);
    }
  }

  public record Declarations(String packageName, List<String> topLevelTypes) {

    public Declarations {
      Objects.requireNonNull(packageName, "packageName");
      topLevelTypes = List.copyOf(Objects.requireNonNull(topLevelTypes, "topLevelTypes"));
    }
  }
}
