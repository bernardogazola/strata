package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  static Map<String, String> staticMembers(List<CtImport> imports, TargetNames names) {
    final Map<String, String> members = new HashMap<>();
    for (final CtImport declaration : imports) {
      if (declaration instanceof CtUnresolvedImport unresolved) {
        final String text = unresolved.getUnresolvedReference();
        final int lastDot = text == null ? -1 : text.lastIndexOf('.');
        if (unresolved.isStatic() && lastDot > 0 && !text.endsWith(ON_DEMAND_SUFFIX)) {
          members.merge(
              text.substring(lastDot + 1),
              names.canonical(text.substring(0, lastDot)),
              ImportSites::smaller);
        }
      } else if (memberDeclaringType(declaration.getReference())
          instanceof CtTypeReference<?> type) {
        members.merge(
            declaration.getReference().getSimpleName(),
            names.canonical(type.getQualifiedName()),
            ImportSites::smaller);
      }
    }
    return members;
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
    return type == null ? null : names.written(type.getQualifiedName());
  }

  private static Target unresolved(CtUnresolvedImport unresolved, TargetNames names) {
    final String text = unresolved.getUnresolvedReference();
    if (text == null || text.isEmpty()) {
      return null;
    }
    if (unresolved.isStatic()) {
      final int lastDot = text.lastIndexOf('.');
      return lastDot > 0 ? names.written(text.substring(0, lastDot)) : null;
    }
    if (text.endsWith(ON_DEMAND_SUFFIX)) {
      return names.typeOnDemand(text.substring(0, text.length() - ON_DEMAND_SUFFIX.length()));
    }
    return names.written(text);
  }

  private static CtTypeReference<?> memberDeclaringType(CtReference reference) {
    return switch (reference) {
      case CtExecutableReference<?> method -> method.getDeclaringType();
      case CtFieldReference<?> field -> field.getDeclaringType();
      case null, default -> null;
    };
  }

  private static String smaller(String first, String second) {
    return CodeElementId.compareCodePoints(first, second) <= 0 ? first : second;
  }

  private static int start(CtElement element) {
    final SourcePosition position = element.getPosition();
    return position.isValidPosition() ? position.getSourceStart() : Integer.MAX_VALUE;
  }
}
