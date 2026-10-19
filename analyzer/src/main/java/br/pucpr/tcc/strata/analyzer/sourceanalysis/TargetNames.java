package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

final class TargetNames {

  private static final String JAVA_PACKAGE = "java.";

  private final SimpleNameIndex index;
  private final Set<String> batchTopLevelTypes;
  private final String ownPackage;
  private final Map<String, String> singleTypeImports;
  private final SimpleNameIndex.Scope scope;
  private final Map<DropReason, Integer> drops;

  TargetNames(
      SimpleNameIndex index,
      Set<String> batchTopLevelTypes,
      String ownPackage,
      Map<String, String> singleTypeImports,
      SimpleNameIndex.Scope scope,
      Map<DropReason, Integer> drops) {
    this.index = index;
    this.batchTopLevelTypes = batchTopLevelTypes;
    this.ownPackage = ownPackage;
    this.singleTypeImports = singleTypeImports;
    this.scope = scope;
    this.drops = drops;
  }

  Target reference(CtTypeReference<?> reference) {
    if (ElementIds.hasLocalSegment(reference.getQualifiedName())) {
      return drop(DropReason.LOCAL_TARGET);
    }
    return named(spelled(reference));
  }

  Target qualifier(CtTypeReference<?> reference) {
    final String qualified = reference.getQualifiedName();
    final int local = ElementIds.localSegmentStart(qualified);
    return local < 0 ? named(spelled(reference)) : named(qualified.substring(0, local));
  }

  Target written(String name) {
    return named(canonical(name));
  }

  Target typeOnDemand(String name) {
    final String canonical = canonical(name);
    return index.declaredTopLevel(canonical).isPresent() ? named(canonical) : null;
  }

  String canonical(String name) {
    return scope.canonical(name, false);
  }

  String spelled(CtTypeReference<?> reference) {
    final String erased = ElementIds.erase(reference, scope);
    return isPlain(reference) && ElementIds.isWrittenSimple(reference) ? bare(erased) : erased;
  }

  Target named(String name) {
    if (!isQualifiedIdentifier(name)) {
      return drop(DropReason.INVALID_NAME);
    }
    final CodeElementId id = CodeElementId.ofType(name);
    if (!id.fitsCeiling()) {
      return drop(DropReason.ID_TOO_LONG);
    }
    final boolean declared = index.declaredTopLevel(name).isPresent();
    if (declared && !ownPackage.isEmpty() && name.indexOf('.') < 0) {
      return drop(DropReason.DEFAULT_PACKAGE_COLLISION);
    }
    return new Target(id, declared, quality(name));
  }

  Target callWithoutTarget() {
    return drop(DropReason.CALL_WITHOUT_TARGET);
  }

  ResolutionQuality quality(String target) {
    final Optional<String> topLevel = index.declaredTopLevel(target);
    if (topLevel.isPresent()) {
      return batchTopLevelTypes.contains(topLevel.get())
          ? ResolutionQuality.RESOLVED
          : ResolutionQuality.INFERRED;
    }
    return target.startsWith(JAVA_PACKAGE)
        ? ResolutionQuality.RESOLVED
        : ResolutionQuality.UNRESOLVED_EXTERNAL;
  }

  private String bare(String name) {
    final int lastDot = name.lastIndexOf('.');
    if (ownPackage.isEmpty()
        || lastDot != ownPackage.length()
        || !name.startsWith(ownPackage)
        || index.declaredTopLevel(name).isPresent()) {
      return name;
    }
    final String simpleName = name.substring(lastDot + 1);
    return singleTypeImports.containsKey(simpleName) ? name : simpleName;
  }

  private static boolean isPlain(CtTypeReference<?> reference) {
    return !(reference instanceof CtArrayTypeReference<?>)
        && !(reference instanceof CtTypeParameterReference)
        && !(reference instanceof CtIntersectionTypeReference<?>);
  }

  private Target drop(DropReason reason) {
    drops.merge(reason, 1, Integer::sum);
    return null;
  }

  static boolean isQualifiedIdentifier(String name) {
    int start = 0;
    while (true) {
      final int dot = name.indexOf('.', start);
      final int end = dot < 0 ? name.length() : dot;
      if (!isIdentifier(name, start, end)) {
        return false;
      }
      if (dot < 0) {
        return true;
      }
      start = dot + 1;
    }
  }

  private static boolean isIdentifier(String name, int start, int end) {
    if (start >= end || !Character.isJavaIdentifierStart(name.codePointAt(start))) {
      return false;
    }
    for (int i = start + Character.charCount(name.codePointAt(start)); i < end; ) {
      final int codePoint = name.codePointAt(i);
      if (!Character.isJavaIdentifierPart(codePoint)) {
        return false;
      }
      i += Character.charCount(codePoint);
    }
    return true;
  }

  record Target(CodeElementId id, boolean declared, ResolutionQuality quality) {}
}
