package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import java.util.Map;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;

final class CallTargets {

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
    final CtExecutableReference<?> executable = invocation.getExecutable();
    CtTypeReference<?> qualifying = qualifying(receiver);
    if (qualifying == null && executable != null) {
      qualifying = executable.getDeclaringType();
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
      default -> captured(receiver.getType());
    };
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
