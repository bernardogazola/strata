package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import java.util.List;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

final class CalleeIds {

  private final BatchDeclarations declarations;
  private final UnitHomes homes;
  private final ExternalPackages platform;

  CalleeIds(BatchDeclarations declarations, UnitHomes homes, ExternalPackages platform) {
    this.declarations = declarations;
    this.homes = homes;
    this.platform = platform;
  }

  CodeElementId of(CtAbstractInvocation<?> site) {
    final CtExecutableReference<?> reference = site.getExecutable();
    if (reference == null
        || reference.getSimpleName().startsWith(CtExecutableReference.LAMBDA_NAME_PREFIX)
        || reference.isConstructor() && isLocalConstructor(reference)) {
      return null;
    }
    final CtExecutable<?> declaration = declarations.executable(reference);
    if (declaration == null || isSynthesized(declaration) || !overloadIsKnown(site, declaration)) {
      return null;
    }
    return declarations.calleeId(declaration, this::id);
  }

  private CodeElementId id(CtExecutable<?> declaration) {
    final CtType<?> owner = ElementIds.methodOwner(declaration);
    if (owner == null) {
      return null;
    }
    final Home home = homes.of(owner);
    if (home == null) {
      return null;
    }
    final CodeElementId id =
        ElementIds.methodId(
            ElementIds.idName(owner),
            ElementIds.methodName(declaration).orElseThrow(),
            declaration,
            home.scope());
    return id.fitsCeiling() ? id : null;
  }

  private static boolean isLocalConstructor(CtExecutableReference<?> reference) {
    final CtTypeReference<?> declaring = reference.getDeclaringType();
    return declaring != null && ElementIds.hasLocalSegment(declaring.getQualifiedName());
  }

  private static boolean isSynthesized(CtExecutable<?> declaration) {
    return declaration.isImplicit() && declaration.getParent() instanceof CtEnum<?>;
  }

  private boolean overloadIsKnown(CtAbstractInvocation<?> site, CtExecutable<?> declaration) {
    final int arguments = site.getArguments().size();
    final List<? extends CtExecutable<?>> candidates = BatchDeclarations.sameName(declaration);
    int compatible = 0;
    boolean competitorsKnown = true;
    for (final CtExecutable<?> candidate : candidates) {
      if (!acceptsArity(candidate, arguments)) {
        continue;
      }
      compatible++;
      competitorsKnown &= candidate == declaration || parametersKnown(candidate);
    }
    if (compatible < 2) {
      return true;
    }
    return competitorsKnown && parametersKnown(declaration) && argumentsKnown(site);
  }

  private static boolean acceptsArity(CtExecutable<?> candidate, int arguments) {
    final List<CtParameter<?>> parameters = candidate.getParameters();
    if (parameters.size() == arguments) {
      return true;
    }
    return !parameters.isEmpty()
        && parameters.getLast().isVarArgs()
        && arguments >= parameters.size() - 1;
  }

  private boolean parametersKnown(CtExecutable<?> executable) {
    for (final CtParameter<?> parameter : executable.getParameters()) {
      if (!known(parameter.getType())) {
        return false;
      }
    }
    return true;
  }

  private boolean argumentsKnown(CtAbstractInvocation<?> site) {
    for (final CtExpression<?> argument : site.getArguments()) {
      if (!argumentKnown(argument)) {
        return false;
      }
    }
    return true;
  }

  private boolean argumentKnown(CtExpression<?> argument) {
    if (argument instanceof CtLiteral<?> literal && literal.getValue() == null) {
      return true;
    }
    if (argument instanceof CtFieldAccess<?> field
        && field.getVariable() != null
        && field.getVariable().getDeclaringType() != null
        && !known(field.getVariable().getDeclaringType())) {
      return false;
    }
    if (argument instanceof CtInvocation<?> call
        && (call.getExecutable() == null || !known(call.getExecutable().getDeclaringType()))) {
      return false;
    }
    return known(argument.getType());
  }

  private boolean known(CtTypeReference<?> type) {
    return switch (type) {
      case null -> false;
      case CtArrayTypeReference<?> array -> known(array.getComponentType());
      case CtTypeParameterReference _ -> true;
      case CtIntersectionTypeReference<?> intersection ->
          intersection.getBounds().stream().allMatch(this::known);
      default -> type.isPrimitive() || knownName(type.getQualifiedName());
    };
  }

  private boolean knownName(String name) {
    return name != null
        && TargetNames.isQualifiedIdentifier(name)
        && (declarations.inBatch(name) || platform.isJdk(name));
  }
}
