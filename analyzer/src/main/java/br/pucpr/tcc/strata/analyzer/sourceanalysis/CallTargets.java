package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;

final class CallTargets {

  private static final Set<String> NULL_TYPE_NAMES = Set.of(CtTypeReference.NULL_TYPE_NAME, "null");

  private CallTargets() {}

  static boolean isSite(CtConstructorCall<?> call) {
    return !call.isImplicit() && !(call.getParent() instanceof CtEnumValue<?>);
  }

  static Target of(CtConstructorCall<?> call, TargetNames names) {
    final CtTypeReference<?> type = call.getType();
    return type == null ? names.callWithoutTarget() : names.reference(type);
  }

  static Target of(
      CtInvocation<?> invocation,
      CodeElementId source,
      Map<String, String> staticImports,
      TargetNames names) {
    final CtExpression<?> receiver = invocation.getTarget();
    if (isMistypedStaticFieldRead(invocation, receiver)) {
      return names.callWithoutTarget();
    }
    final CtExecutableReference<?> executable = invocation.getExecutable();
    CtTypeReference<?> qualifying = qualifying(receiver);
    if (qualifying == null && executable != null) {
      qualifying = known(executable.getDeclaringType());
    }
    if (qualifying != null) {
      return names.qualifier(qualifying);
    }
    if (receiver instanceof CtThisAccess<?> self && self.isImplicit() && executable != null) {
      final String imported = staticImports.get(executable.getSimpleName());
      return names.named(imported != null ? imported : source.sortKey());
    }
    return names.callWithoutTarget();
  }

  private static CtTypeReference<?> qualifying(CtExpression<?> receiver) {
    return switch (receiver) {
      case null -> null;
      case CtSuperAccess<?> parent -> parent.getType();
      case CtTypeAccess<?> type -> type.getAccessedType();
      default -> captured(known(staticType(receiver)));
    };
  }

  private static boolean isMistypedStaticFieldRead(
      CtInvocation<?> invocation, CtExpression<?> receiver) {
    if (!(receiver instanceof CtFieldRead<?> read)
        || !(read.getTarget() instanceof CtTypeAccess<?>)
        || read.getVariable() == null) {
      return false;
    }
    final CtTypeReference<?> type = read.getType();
    final CtTypeReference<?> declaring = read.getVariable().getDeclaringType();
    final CtType<?> caller = invocation.getParent(CtType.class);
    return type != null
        && declaring != null
        && caller != null
        && type.getQualifiedName().equals(caller.getQualifiedName())
        && !declaring.getQualifiedName().equals(type.getQualifiedName());
  }

  private static CtTypeReference<?> staticType(CtExpression<?> receiver) {
    final CtTypeReference<?> type = receiver.getType();
    if (type == null
        || !ElementIds.OBJECT.equals(type.getQualifiedName())
        || !(receiver instanceof CtVariableRead<?> read)
        || !(read.getVariable() instanceof CtLocalVariableReference<?> local)) {
      return type;
    }
    final CtLocalVariable<?> declaration = local.getDeclaration();
    if (declaration == null || !declaration.isInferred()) {
      return type;
    }
    final CtExpression<?> initializer = declaration.getDefaultExpression();
    return initializer == null ? null : initializer.getType();
  }

  private static CtTypeReference<?> known(CtTypeReference<?> type) {
    return type == null || NULL_TYPE_NAMES.contains(type.getQualifiedName()) ? null : type;
  }

  private static CtTypeReference<?> captured(CtTypeReference<?> type) {
    return type instanceof CtWildcardReference wildcard
            && wildcard.isUpper()
            && !wildcard.isDefaultBoundingType()
            && wildcard.getBoundingType() != null
        ? wildcard.getBoundingType()
        : type;
  }
}
