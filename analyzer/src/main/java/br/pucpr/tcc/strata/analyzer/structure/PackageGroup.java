package br.pucpr.tcc.strata.analyzer.structure;

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
    throw new UnsupportedOperationException("ainda não implementado: o agrupamento de pacotes");
  }

  public GraphNodeKind kind() {
    throw new UnsupportedOperationException("ainda não implementado: o agrupamento de pacotes");
  }
}
