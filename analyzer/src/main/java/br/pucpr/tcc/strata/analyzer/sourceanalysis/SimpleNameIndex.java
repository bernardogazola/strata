package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

final class SimpleNameIndex {

  private final Set<String> topLevelTypes;

  private SimpleNameIndex(Set<String> topLevelTypes) {
    this.topLevelTypes = Set.copyOf(topLevelTypes);
  }

  static SimpleNameIndex of(List<SourceFile> files) {
    final Set<String> types = new HashSet<>();
    for (final SourceFile file : files) {
      final SourceFile.Declarations declarations = file.declarations();
      if (declarations != null) {
        for (final String type : declarations.topLevelTypes()) {
          types.add(qualify(declarations.packageName(), type));
        }
      }
    }
    return new SimpleNameIndex(types);
  }

  UnaryOperator<String> scope(String ownPackage, Set<String> onDemandPackages) {
    Objects.requireNonNull(ownPackage, "ownPackage");
    final Set<String> imported = Set.copyOf(onDemandPackages);
    return name -> canonicalize(name, ownPackage, imported);
  }

  String canonicalize(String name, String ownPackage, Set<String> onDemandPackages) {
    if (name.indexOf('.') < 0) {
      return resolveBare(name, simple -> inScope(simple, ownPackage, onDemandPackages))
          .orElse(name);
    }
    final Optional<String> prefixed = longestInternalPrefix(name);
    if (prefixed.isPresent()) {
      return prefixed.get();
    }
    if (!ownPackage.isEmpty() && name.startsWith(ownPackage + ".")) {
      final String rest = name.substring(ownPackage.length() + 1);
      if (rest.indexOf('.') < 0 && !topLevelTypes.contains(qualify(ownPackage, topOf(rest)))) {
        return resolveBare(rest, simple -> onlyOnDemand(simple, onDemandPackages)).orElse(name);
      }
    }
    return name;
  }

  private static Optional<String> resolveBare(
      String name, Function<String, Optional<String>> resolver) {
    final Optional<String> whole = resolver.apply(name);
    final String top = topOf(name);
    if (whole.isPresent() || top.length() == name.length()) {
      return whole;
    }
    return resolver.apply(top).map(resolved -> resolved + name.substring(top.length()));
  }

  private Optional<String> inScope(
      String simpleName, String ownPackage, Set<String> onDemandPackages) {
    final String own = qualify(ownPackage, simpleName);
    return topLevelTypes.contains(own)
        ? Optional.of(own)
        : onlyOnDemand(simpleName, onDemandPackages);
  }

  private Optional<String> onlyOnDemand(String simpleName, Set<String> onDemandPackages) {
    String only = null;
    for (final String imported : onDemandPackages) {
      final String candidate = qualify(imported, simpleName);
      if (topLevelTypes.contains(candidate)) {
        if (only != null) {
          return Optional.empty();
        }
        only = candidate;
      }
    }
    return Optional.ofNullable(only);
  }

  private Optional<String> longestInternalPrefix(String name) {
    for (int end = name.length(); end > 0; end = name.lastIndexOf('.', end - 1)) {
      final String prefix = name.substring(0, end);
      if (topLevelTypes.contains(prefix)) {
        return Optional.of(prefix + name.substring(end).replace('.', '$'));
      }
    }
    return Optional.empty();
  }

  private static String topOf(String name) {
    final int dollar = name.indexOf('$');
    return dollar <= 0 ? name : name.substring(0, dollar);
  }

  private static String qualify(String packageName, String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }
}
