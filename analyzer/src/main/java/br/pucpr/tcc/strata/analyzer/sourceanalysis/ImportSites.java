package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import spoon.experimental.CtUnresolvedImport;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeMemberWildcardImportReference;
import spoon.reflect.reference.CtTypeReference;

final class ImportSites {

  private static final String JAVA_SUFFIX = ".java";
  private static final String ON_DEMAND_SUFFIX = ".*";

  private ImportSites() {}

  static CtType<?> owner(String relativePath, CtCompilationUnit unit) {
    final String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
    final String baseName =
        fileName.endsWith(JAVA_SUFFIX)
            ? fileName.substring(0, fileName.length() - JAVA_SUFFIX.length())
            : fileName;
    CtType<?> first = null;
    for (final CtType<?> top : unit.getDeclaredTypes()) {
      if (!ElementIds.isElementType(top)) {
        continue;
      }
      if (top.getSimpleName().equals(baseName)) {
        return top;
      }
      if (first == null || start(top) < start(first)) {
        first = top;
      }
    }
    return first;
  }

  static void record(List<CtImport> imports, CtType<?> owner, OccurrenceRecorder recorder) {
    final CodeElementId source = CodeElementId.ofType(ElementIds.idName(owner));
    for (final CtImport declaration : imports) {
      recorder.imported(declaration, source, target(declaration, recorder.names()));
    }
  }

  static void recordRetained(
      List<String> retained, List<CtImport> imports, CtType<?> owner, OccurrenceRecorder recorder) {
    if (retained.isEmpty()) {
      return;
    }
    final Set<String> kept = new HashSet<>();
    for (final CtImport declaration : imports) {
      if (declaration instanceof CtUnresolvedImport unresolved && unresolved.isStatic()) {
        kept.add(unresolved.getUnresolvedReference());
      }
    }
    final CodeElementId source = CodeElementId.ofType(ElementIds.idName(owner));
    for (final String text : new TreeSet<>(retained)) {
      final int lastDot = text.lastIndexOf('.');
      if (lastDot > 0 && !kept.contains(text)) {
        recorder.imported(null, source, recorder.names().imported(text.substring(0, lastDot)));
      }
    }
  }

  private static Target target(CtImport declaration, TargetNames names) {
    if (declaration instanceof CtUnresolvedImport unresolved) {
      return unresolved(unresolved, names);
    }
    final CtReference reference = declaration.getReference();
    final CtTypeReference<?> type =
        switch (declaration.getImportKind()) {
          case TYPE -> reference instanceof CtTypeReference<?> named ? named : null;
          case METHOD, FIELD -> memberDeclaringType(reference);
          case ALL_STATIC_MEMBERS ->
              reference instanceof CtTypeMemberWildcardImportReference members
                  ? members.getTypeReference()
                  : null;
          case ALL_TYPES, UNRESOLVED, MODULE -> null;
        };
    return type == null ? null : names.imported(type.getQualifiedName());
  }

  private static Target unresolved(CtUnresolvedImport unresolved, TargetNames names) {
    final String text = unresolved.getUnresolvedReference();
    if (text == null || text.isEmpty()) {
      return null;
    }
    if (unresolved.isStatic()) {
      final int lastDot = text.lastIndexOf('.');
      return lastDot > 0 ? names.imported(text.substring(0, lastDot)) : null;
    }
    if (text.endsWith(ON_DEMAND_SUFFIX)) {
      return names.typeOnDemand(text.substring(0, text.length() - ON_DEMAND_SUFFIX.length()));
    }
    return names.imported(text);
  }

  private static CtTypeReference<?> memberDeclaringType(CtReference reference) {
    return switch (reference) {
      case CtExecutableReference<?> method -> method.getDeclaringType();
      case CtFieldReference<?> field -> field.getDeclaringType();
      case null, default -> null;
    };
  }

  private static int start(CtElement element) {
    final SourcePosition position = element.getPosition();
    return position.isValidPosition() ? position.getSourceStart() : Integer.MAX_VALUE;
  }
}
