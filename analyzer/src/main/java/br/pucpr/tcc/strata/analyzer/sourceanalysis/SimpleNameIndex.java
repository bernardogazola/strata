package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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

  Scope scope(
      String ownPackage, Set<String> onDemandPackages, Map<String, String> singleTypeImports) {
    Objects.requireNonNull(ownPackage, "ownPackage");
    final Set<String> imported = Set.copyOf(onDemandPackages);
    final Map<String, String> single = Map.copyOf(singleTypeImports);
    return (name, writtenSimple) ->
        writtenSimple
            ? canonicalizeSimple(name, ownPackage, imported, single)
            : canonicalize(name, ownPackage, imported);
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
    final int firstDot = name.indexOf('.');
    return resolveBare(
            name.substring(0, firstDot), simple -> inScope(simple, ownPackage, onDemandPackages))
        .map(type -> type + "$" + name.substring(firstDot + 1).replace('.', '$'))
        .orElse(name);
  }

  private String canonicalizeSimple(
      String name,
      String ownPackage,
      Set<String> onDemandPackages,
      Map<String, String> singleTypeImports) {
    final int lastDot = name.lastIndexOf('.');
    final String simpleName = name.substring(lastDot + 1);
    final String imported = singleTypeImports.get(simpleName);
    if (imported != null) {
      return topLevelTypes.contains(imported)
          ? imported
          : canonicalize(name, ownPackage, onDemandPackages);
    }
    final boolean outsideOwnPackage = lastDot > 0 && !name.substring(0, lastDot).equals(ownPackage);
    final String own = qualify(ownPackage, simpleName);
    if (outsideOwnPackage && topLevelTypes.contains(own)) {
      return own;
    }
    return canonicalize(name, ownPackage, onDemandPackages);
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
    final int firstDot = name.indexOf('.');
    for (int end = name.length(); end > firstDot; end = name.lastIndexOf('.', end - 1)) {
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

  @FunctionalInterface
  interface Scope {

    String canonical(String name, boolean writtenSimple);
  }
}
