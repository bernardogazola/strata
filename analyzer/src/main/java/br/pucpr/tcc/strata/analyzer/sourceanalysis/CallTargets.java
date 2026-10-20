package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnqualifiedCalls.Qualifying;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnqualifiedCalls.Variable;
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
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.reference.CtWildcardReference;

final class CallTargets {

  private static final Set<String> NULL_TYPE_NAMES = Set.of(CtTypeReference.NULL_TYPE_NAME, "null");

  private static final String VAR = "var";

  private final TargetNames names;
  private final UnqualifiedCalls unqualified;
  private final BatchDeclarations declarations;
  private final UnitHomes homes;

  CallTargets(
      TargetNames names,
      UnqualifiedCalls unqualified,
      BatchDeclarations declarations,
      UnitHomes homes) {
    this.names = names;
    this.unqualified = unqualified;
    this.declarations = declarations;
    this.homes = homes;
  }

  static boolean isSite(CtConstructorCall<?> call) {
    return !call.isImplicit() && !(call.getParent() instanceof CtEnumValue<?>);
  }

  Target of(CtConstructorCall<?> call) {
    final CtTypeReference<?> type = call.getType();
    return type == null ? names.callWithoutTarget() : names.reference(type);
  }

  Target of(CtInvocation<?> invocation, CodeElementId source) {
    final CtExpression<?> receiver = invocation.getTarget();
    final CtExecutableReference<?> executable = invocation.getExecutable();
    if (executable != null && !executable.isConstructor() && isUnqualified(receiver)) {
      final Qualifying qualifying = unqualified.qualifying(invocation, executable.getSimpleName());
      if (qualifying.name() != null) {
        return names.exact(qualifying.name());
      }
      final Target spoon = byReceiver(invocation, receiver, source);
      return spoon != null && qualifying.outerOfUnknown().contains(spoon.id())
          ? spoon.uncertain()
          : spoon;
    }
    return byReceiver(invocation, receiver, source);
  }

  private Target byReceiver(
      CtInvocation<?> invocation, CtExpression<?> receiver, CodeElementId source) {
    return switch (receiver) {
      case CtSuperAccess<?> parent -> superclass(invocation, parent, source);
      case CtFieldRead<?> read -> field(invocation, read, source);
      case CtVariableRead<?> read -> variable(read);
      case CtInvocation<?> chained -> chained(invocation, chained, source);
      case CtConstructorCall<?> created when created.getType() != null ->
          names.qualifier(created.getType(), true);
      case CtTypeAccess<?> access when !access.isImplicit() ->
          typeAccess(invocation, access, source);
      case null, default -> fallback(invocation, receiver, source);
    };
  }

  private Target typeAccess(
      CtInvocation<?> invocation, CtTypeAccess<?> access, CodeElementId source) {
    final CtTypeReference<?> accessed = access.getAccessedType();
    if (accessed != null && ElementIds.isWrittenSimple(accessed)) {
      final String name = accessed.getSimpleName();
      final Variable variable = unqualified.variable(access, name);
      final Target target;
      if (variable.declaration() != null) {
        target = declared(variable.declaration());
      } else if (variable.field() != null) {
        target = indexed(access, variable.field(), name);
      } else {
        return fallback(invocation, access, source);
      }
      return target != null && variable.uncertain() ? target.uncertain() : target;
    }
    return fallback(invocation, access, source);
  }

  private Target declared(CtVariable<?> declaration) {
    final CtTypeReference<?> type = declaration.getType();
    if (unknown(type, declaration.getSimpleName())) {
      return names.callWithoutTarget();
    }
    if (declaration instanceof CtLocalVariable<?> local
        && (VAR.equals(type.getSimpleName()) || local.isInferred())) {
      return byInitializer(local);
    }
    if (declaration instanceof CtField<?> field && isNamed(type)) {
      final Home home = homes.of(field);
      if (home != null) {
        return names.copied(type, home);
      }
    }
    return names.qualifier(captured(type), !type.isImplicit());
  }

  private Target indexed(CtElement site, FieldLookup found, String name) {
    final CtType<?> owner =
        declarations.type(site.getFactory().Type().createReference(found.declaringType()));
    final CtField<?> declaration = owner == null ? null : fieldOf(owner, name);
    return declaration != null ? declared(declaration) : names.recovered(found.typeName());
  }

  private static CtField<?> fieldOf(CtType<?> owner, String name) {
    final CtField<?> field = owner.getField(name);
    return field == null && owner instanceof CtEnum<?> enumeration
        ? enumeration.getEnumValue(name)
        : field;
  }

  private static boolean isUnqualified(CtExpression<?> receiver) {
    return receiver == null
        || receiver instanceof CtThisAccess<?> self && self.isImplicit()
        || receiver instanceof CtTypeAccess<?> access && access.isImplicit();
  }

  private Target superclass(
      CtInvocation<?> invocation, CtSuperAccess<?> parent, CodeElementId source) {
    final CtType<?> caller = invocation.getParent(CtType.class);
    final CtTypeReference<?> written = caller == null ? null : caller.getSuperclass();
    final CtTypeReference<?> accessed = parent.getType();
    if (written != null
        && accessed != null
        && written.getQualifiedName().equals(accessed.getQualifiedName())) {
      return names.qualifier(written, true);
    }
    return fallback(invocation, parent, source);
  }

  private Target field(CtInvocation<?> invocation, CtFieldRead<?> read, CodeElementId source) {
    final CtFieldReference<?> variable = read.getVariable();
    if (variable == null) {
      return fallback(invocation, read, source);
    }
    final CtField<?> declaration = declarations.field(variable);
    if (declaration != null && isNamed(declaration.getType())) {
      final Home home = homes.of(declaration);
      if (home != null) {
        return names.copied(declaration.getType(), home);
      }
    }
    final String member = variable.getSimpleName();
    final CtTypeReference<?> type = read.getType();
    final boolean typedAsCaller = isCaller(type, invocation);
    if (!unknown(type, member) && !typedAsCaller) {
      return fallback(invocation, read, source);
    }
    final CtExpression<?> target = read.getTarget();
    if (target instanceof CtTypeAccess<?> access && !access.isImplicit()) {
      return names.memberOrField(names.canonical(access.getAccessedType()), member);
    }
    final FieldLookup found = lookup(invocation, target, member);
    if (found.answer() == MemberAnswer.YES) {
      return names.recovered(found.typeName());
    }
    if (target instanceof CtTypeAccess<?> access && access.getAccessedType() != null) {
      return names.memberOrField(names.canonical(access.getAccessedType()), member);
    }
    return typedAsCaller ? fallback(invocation, read, source) : names.callWithoutTarget();
  }

  private FieldLookup lookup(CtInvocation<?> invocation, CtExpression<?> target, String member) {
    final boolean simpleName =
        target == null
            || target instanceof CtThisAccess<?> self && self.isImplicit()
            || target instanceof CtTypeAccess<?> access && access.isImplicit();
    if (simpleName) {
      return unqualified.field(invocation, member);
    }
    final CtTypeReference<?> owner = target.getType();
    return owner == null || !isNamed(owner) ? FieldLookup.UNKNOWN : names.field(owner, member);
  }

  private Target variable(CtVariableRead<?> read) {
    final CtVariableReference<?> variable = read.getVariable();
    if (variable == null) {
      return names.callWithoutTarget();
    }
    final CtTypeReference<?> type = read.getType();
    final boolean unknown = unknown(type, variable.getSimpleName());
    if (unknown || names.isOwnPackageGuess(type)) {
      final CtVariable<?> declaration =
          LexicalScope.local(read, variable.getSimpleName()).declaration();
      if (declaration != null) {
        return declared(declaration);
      }
      if (unknown) {
        return names.callWithoutTarget();
      }
    }
    final boolean literalVar = VAR.equals(type.getSimpleName());
    if (literalVar || ElementIds.OBJECT.equals(type.getQualifiedName())) {
      final CtLocalVariable<?> local =
          variable instanceof CtLocalVariableReference<?> reference
              ? reference.getDeclaration()
              : null;
      if (literalVar || local != null && local.isInferred()) {
        return byInitializer(local);
      }
    }
    return names.qualifier(captured(type), !type.isImplicit());
  }

  private Target byInitializer(CtLocalVariable<?> local) {
    final CtTypeReference<?> initialized = initializerType(local);
    return initialized == null
        ? names.callWithoutTarget()
        : names.qualifier(captured(initialized), true);
  }

  private Target chained(CtInvocation<?> invocation, CtInvocation<?> inner, CodeElementId source) {
    final CtExecutable<?> declaration = declarations.executable(inner.getExecutable());
    if (declaration instanceof CtMethod<?> method && isNamed(method.getType())) {
      final Home home = homes.of(method);
      if (home != null) {
        return names.copied(method.getType(), home);
      }
    }
    return fallback(invocation, inner, source);
  }

  private Target fallback(
      CtInvocation<?> invocation, CtExpression<?> receiver, CodeElementId source) {
    final CtExecutableReference<?> executable = invocation.getExecutable();
    CtTypeReference<?> qualifying =
        switch (receiver) {
          case null -> null;
          case CtSuperAccess<?> parent -> parent.getType();
          case CtTypeAccess<?> type -> type.getAccessedType();
          default -> captured(known(receiver.getType()));
        };
    final boolean variable =
        receiver instanceof CtVariableRead<?> && !(receiver instanceof CtSuperAccess<?>);
    if (qualifying == null && executable != null && !variable) {
      qualifying = known(executable.getDeclaringType());
    }
    if (qualifying != null) {
      return names.qualifier(
          qualifying, receiver instanceof CtTypeAccess<?> access && !access.isImplicit());
    }
    if (receiver instanceof CtThisAccess<?> self && self.isImplicit() && executable != null) {
      return names.exact(source.sortKey());
    }
    return names.callWithoutTarget();
  }

  private static boolean isCaller(CtTypeReference<?> type, CtInvocation<?> invocation) {
    final CtType<?> caller = invocation.getParent(CtType.class);
    return type != null
        && caller != null
        && type.getQualifiedName().equals(caller.getQualifiedName());
  }

  private static boolean unknown(CtTypeReference<?> type, String variableName) {
    return type == null
        || NULL_TYPE_NAMES.contains(type.getQualifiedName())
        || type.getSimpleName().equals(variableName);
  }

  private static CtTypeReference<?> initializerType(CtLocalVariable<?> declaration) {
    final CtExpression<?> initializer =
        declaration == null ? null : declaration.getDefaultExpression();
    if (initializer instanceof CtConstructorCall<?> call && call.getType() != null) {
      return call.getType();
    }
    final CtTypeReference<?> type = initializer == null ? null : known(initializer.getType());
    return type == null || VAR.equals(type.getSimpleName()) ? null : type;
  }

  private static boolean isNamed(CtTypeReference<?> type) {
    return type != null
        && !(type instanceof CtTypeParameterReference)
        && !(type instanceof CtArrayTypeReference<?>)
        && !(type instanceof CtIntersectionTypeReference<?>)
        && !type.isPrimitive();
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
