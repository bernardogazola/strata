package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import spoon.experimental.CtUnresolvedImport;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtModuleReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeMemberWildcardImportReference;
import spoon.reflect.reference.CtTypeReference;

final class ImportScope {

  private static final String ON_DEMAND_SUFFIX = ".*";

  private final String ownPackage;
  private final Map<String, String> singleType;
  private final List<String> onDemand;
  private final Map<String, List<String>> singleStatic;
  private final List<String> staticOnDemand;
  private final boolean moduleImport;

  private ImportScope(
      String ownPackage,
      Map<String, String> singleType,
      Collection<String> onDemand,
      Map<String, ? extends Collection<String>> singleStatic,
      Collection<String> staticOnDemand,
      boolean moduleImport) {
    this.ownPackage = Objects.requireNonNull(ownPackage, "ownPackage");
    this.singleType = Map.copyOf(singleType);
    this.onDemand = List.copyOf(new TreeSet<>(onDemand));
    final Map<String, List<String>> statics = new HashMap<>();
    singleStatic.forEach((member, owners) -> statics.put(member, List.copyOf(owners)));
    this.singleStatic = Map.copyOf(statics);
    this.staticOnDemand = List.copyOf(new TreeSet<>(staticOnDemand));
    this.moduleImport = moduleImport;
  }

  static ImportScope of(String ownPackage, Set<String> onDemand, Map<String, String> singleType) {
    return new ImportScope(ownPackage, singleType, onDemand, Map.of(), List.of(), false);
  }

  static ImportScope of(UnitShape shape) {
    final Texts texts = new Texts();
    shape.singleTypeImports().forEach(texts::singleType);
    texts.onDemand.addAll(shape.onDemandImports());
    shape.singleStaticImports().forEach(texts::singleStatic);
    texts.staticOnDemand.addAll(shape.staticOnDemandImports());
    texts.moduleImport = shape.moduleImport();
    return texts.scope(shape.packageName());
  }

  static ImportScope of(CtCompilationUnit unit, String ownPackage, List<String> retainedStatics) {
    final Texts texts = new Texts();
    for (final CtImport declaration : unit.getImports()) {
      if (declaration instanceof CtUnresolvedImport unresolved) {
        texts.unresolved(unresolved);
      } else {
        texts.resolved(declaration.getReference());
      }
    }
    retainedStatics.forEach(texts::singleStatic);
    return texts.scope(ownPackage);
  }

  String ownPackage() {
    return ownPackage;
  }

  String singleType(String simpleName) {
    return singleType.get(simpleName);
  }

  List<String> onDemand() {
    return onDemand;
  }

  List<String> singleStatic(String member) {
    return singleStatic.getOrDefault(member, List.of());
  }

  List<String> staticOnDemand() {
    return staticOnDemand;
  }

  boolean moduleImport() {
    return moduleImport;
  }

  private static final class Texts {

    private final Map<String, String> singleType = new HashMap<>();
    private final List<String> onDemand = new ArrayList<>();
    private final Map<String, TreeSet<String>> singleStatic = new TreeMap<>();
    private final List<String> staticOnDemand = new ArrayList<>();
    private boolean moduleImport;

    private void singleType(String text) {
      singleType.merge(
          simpleName(text), text, (left, right) -> left.compareTo(right) <= 0 ? left : right);
    }

    private void singleStatic(String text) {
      final int lastDot = text.lastIndexOf('.');
      if (lastDot > 0) {
        singleStatic(text.substring(0, lastDot), text.substring(lastDot + 1));
      }
    }

    private void singleStatic(String owner, String member) {
      singleStatic.computeIfAbsent(member, _ -> new TreeSet<>()).add(owner);
    }

    private void unresolved(CtUnresolvedImport unresolved) {
      final String text = unresolved.getUnresolvedReference();
      if (text == null) {
        return;
      }
      final boolean star = text.endsWith(ON_DEMAND_SUFFIX);
      final String name =
          star ? text.substring(0, text.length() - ON_DEMAND_SUFFIX.length()) : text;
      if (unresolved.isStatic()) {
        if (star) {
          staticOnDemand.add(name);
        } else {
          singleStatic(name);
        }
      } else if (star) {
        onDemand.add(name);
      } else {
        singleType(name);
      }
    }

    private void resolved(CtReference reference) {
      switch (reference) {
        case CtTypeMemberWildcardImportReference wildcard ->
            staticOnDemand.add(wildcard.getTypeReference().getQualifiedName());
        case CtTypeReference<?> type ->
            singleType.merge(
                type.getSimpleName(),
                type.getQualifiedName(),
                (left, right) -> left.compareTo(right) <= 0 ? left : right);
        case CtPackageReference imported -> onDemand.add(imported.getQualifiedName());
        case CtExecutableReference<?> method when method.getDeclaringType() != null ->
            singleStatic(method.getDeclaringType().getQualifiedName(), method.getSimpleName());
        case CtFieldReference<?> field when field.getDeclaringType() != null ->
            singleStatic(field.getDeclaringType().getQualifiedName(), field.getSimpleName());
        case CtModuleReference _ -> moduleImport = true;
        case null, default -> {}
      }
    }

    private ImportScope scope(String ownPackage) {
      return new ImportScope(
          ownPackage, singleType, onDemand, singleStatic, staticOnDemand, moduleImport);
    }

    private static String simpleName(String text) {
      return text.substring(text.lastIndexOf('.') + 1);
    }
  }
}
