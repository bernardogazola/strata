package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.util.Objects;

public record PackageGroup(String name, boolean isPrefix) {

  public PackageGroup {
    Objects.requireNonNull(name, "name");
    if (isPrefix && name.isEmpty()) {
      throw new IllegalArgumentException("um grupo de prefixo tem prefixo não vazio");
    }
  }

  public static PackageGroup exact(String qualifiedName) {
    return new PackageGroup(qualifiedName, false);
  }

  public static PackageGroup prefix(String prefix) {
    return new PackageGroup(prefix, true);
  }

  public String nodeId() {
    return isPrefix ? SliceNode.PREFIX_ID_PREFIX + name : CodeElementId.ofPackage(name).value();
  }

  public GraphNodeKind kind() {
    return isPrefix ? GraphNodeKind.PACKAGE_PREFIX : GraphNodeKind.PACKAGE;
  }
}
