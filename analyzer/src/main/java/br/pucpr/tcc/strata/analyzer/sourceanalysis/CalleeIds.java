package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

final class CalleeIds {

  private final BatchDeclarations declarations;
  private final SimpleNameIndex index;
  private final UnitHomes homes;
  private final ExternalPackages platform;

  private final Map<String, Chain> chains = new HashMap<>();

  private final Map<CtType<?>, List<String>> written = new IdentityHashMap<>();

  CalleeIds(BatchDeclarations declarations, UnitHomes homes, ExternalPackages platform) {
    this.declarations = declarations;
    this.index = declarations.index();
    this.homes = homes;
    this.platform = platform;
  }

  CodeElementId of(CtAbstractInvocation<?> site, CodeElementId target) {
    final CtExecutableReference<?> reference = site.getExecutable();
    if (reference == null
        || reference.getSimpleName().startsWith(CtExecutableReference.LAMBDA_NAME_PREFIX)
        || reference.isConstructor() && isLocalConstructor(reference)) {
      return null;
    }
    final CtExecutable<?> declaration = declarations.executable(reference);
    if (declaration == null || isSynthesized(declaration)) {
      return null;
    }
    final CtType<?> declaring = declaration.getParent(CtType.class);
    final Chain qualifying = declaring == null ? null : qualifying(site, reference, target);
    if (qualifying == null
        || !ownedBy(qualifying, declaration, declaring)
        || !overloadIsKnown(site, declaration, declaring, qualifying)) {
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

  private static boolean ownedBy(
      Chain qualifying, CtExecutable<?> declaration, CtType<?> declaring) {
    return declaration instanceof CtConstructor<?>
        ? qualifying.isSelf(declaring)
        : qualifying.contains(declaring);
  }

  private Chain qualifying(
      CtAbstractInvocation<?> site, CtExecutableReference<?> reference, CodeElementId target) {
    if (site instanceof CtInvocation<?> invocation
        && !reference.isConstructor()
        && isUnqualified(invocation.getTarget())) {
      return unqualified(invocation, reference.getSimpleName(), target);
    }
    final CtType<?> local = localReceiver(site);
    if (local != null) {
      final Home home = homes.of(local);
      return home == null ? null : localChain(local, writtenSupertypes(local, home));
    }
    return chainOf(target.sortKey());
  }

  private static boolean isUnqualified(CtExpression<?> receiver) {
    return receiver == null
        || receiver instanceof CtThisAccess<?> self && self.isImplicit()
        || receiver instanceof CtTypeAccess<?> access && access.isImplicit();
  }

  private Chain unqualified(CtInvocation<?> site, String name, CodeElementId target) {
    final Home home = homes.of(site);
    if (home == null) {
      return null;
    }
    for (CtType<?> frame = site.getParent(CtType.class);
        frame != null;
        frame = frame.getParent(CtType.class)) {
      final boolean element = ElementIds.isElementType(frame);
      final List<String> written = element ? List.of() : writtenSupertypes(frame, home);
      if (provesMember(frame, written, name)) {
        if (!target.equals(cut(frame))) {
          return null;
        }
        return element ? chainOf(ElementIds.idName(frame)) : localChain(frame, written);
      }
    }
    return chainOf(target.sortKey());
  }

  private boolean provesMember(CtType<?> frame, List<String> written, String name) {
    if (ElementIds.isElementType(frame)) {
      return index.hasMethod(ElementIds.idName(frame), name) == MemberAnswer.YES;
    }
    if (!frame.getMethodsByName(name).isEmpty()) {
      return true;
    }
    for (final String supertype : written) {
      if (supertype != null
          && index.type(supertype) != null
          && index.hasMethod(supertype, name) == MemberAnswer.YES) {
        return true;
      }
    }
    return false;
  }

  private static CodeElementId cut(CtType<?> frame) {
    final CtType<?> element = ElementIds.nearestElementType(frame);
    return element == null ? null : CodeElementId.ofType(ElementIds.idName(element));
  }

  private CtType<?> localReceiver(CtAbstractInvocation<?> site) {
    if (!(site instanceof CtInvocation<?> invocation)) {
      return null;
    }
    final CtExpression<?> receiver = invocation.getTarget();
    if (receiver instanceof CtNewClass<?> created) {
      return created.getAnonymousClass();
    }
    final CtTypeReference<?> type = receiver == null ? null : receiver.getType();
    if (type == null || !ElementIds.hasLocalSegment(type.getQualifiedName())) {
      return null;
    }
    final CtType<?> found = declarations.type(type);
    return found == null || ElementIds.isElementType(found) ? null : found;
  }

  private List<String> writtenSupertypes(CtType<?> type, Home home) {
    return written.computeIfAbsent(type, key -> resolveWritten(key, home));
  }

  private static List<String> resolveWritten(CtType<?> type, Home home) {
    final List<CtTypeReference<?>> references = new ArrayList<>();
    if (type.getSuperclass() != null) {
      references.add(type.getSuperclass());
    }
    references.addAll(type.getSuperInterfaces());
    final List<String> names = new ArrayList<>();
    for (final CtTypeReference<?> reference : references) {
      names.add(
          ElementIds.hasLocalSegment(reference.getQualifiedName())
              ? null
              : ElementIds.resolveLeaf(reference, home.scope()).name());
    }
    return names;
  }

  private Chain chainOf(String name) {
    return chains.computeIfAbsent(
        name,
        key -> {
          final Set<String> types = new LinkedHashSet<>();
          final boolean complete = collect(key, types);
          return new Chain(null, key, Set.copyOf(types), complete);
        });
  }

  private Chain localChain(CtType<?> frame, List<String> written) {
    final Set<String> types = new LinkedHashSet<>();
    boolean complete = true;
    for (final String supertype : written) {
      complete &= collect(supertype, types);
    }
    return new Chain(frame, null, Set.copyOf(types), complete);
  }

  private boolean collect(String name, Set<String> types) {
    if (name == null) {
      return false;
    }
    boolean complete = true;
    final Deque<String> pending = new ArrayDeque<>();
    pending.add(name);
    while (!pending.isEmpty()) {
      final String current = pending.poll();
      final IndexedType type = index.type(current);
      if (type == null) {
        complete &= SimpleNameIndex.isRoot(current);
      } else if (types.add(type.id())) {
        complete &= type.structured();
        for (final String supertype : index.supertypesOf(type)) {
          if (supertype == null) {
            complete = false;
          } else {
            pending.add(supertype);
          }
        }
      }
    }
    return complete;
  }

  private record Chain(CtType<?> local, String self, Set<String> types, boolean complete) {

    boolean isSelf(CtType<?> type) {
      return type == local
          || self != null && ElementIds.isElementType(type) && self.equals(ElementIds.idName(type));
    }

    boolean contains(CtType<?> type) {
      return type == local
          || ElementIds.isElementType(type) && types.contains(ElementIds.idName(type));
    }
  }

  private boolean overloadIsKnown(
      CtAbstractInvocation<?> site,
      CtExecutable<?> declaration,
      CtType<?> declaring,
      Chain qualifying) {
    if (declaration instanceof CtMethod<?>
        && competes(qualifying, declaring, declaration.getSimpleName())) {
      return exactlyApplicable(site, declaration);
    }
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

  private boolean competes(Chain qualifying, CtType<?> declaring, String name) {
    if (!qualifying.complete()) {
      return true;
    }
    final CtType<?> local = qualifying.local();
    if (local != null && local != declaring && !local.getMethodsByName(name).isEmpty()) {
      return true;
    }
    final String declaringId =
        ElementIds.isElementType(declaring) ? ElementIds.idName(declaring) : null;
    for (final String type : qualifying.types()) {
      if (!type.equals(declaringId) && declares(type, name, type.equals(qualifying.self()))) {
        return true;
      }
    }
    return false;
  }

  private boolean declares(String typeId, String name, boolean self) {
    final IndexedType type = index.type(typeId);
    final int at = type == null ? -1 : type.methods().find(name);
    return at >= 0
        && (self || MemberTable.visibility(type.methods().flags(at)) != MemberTable.PRIVATE);
  }

  private boolean exactlyApplicable(CtAbstractInvocation<?> site, CtExecutable<?> declaration) {
    final List<CtParameter<?>> parameters = declaration.getParameters();
    final List<CtExpression<?>> arguments = site.getArguments();
    final Home caller = homes.of(site);
    final Home callee = homes.of(declaration);
    if (parameters.size() != arguments.size() || caller == null || callee == null) {
      return false;
    }
    for (int at = 0; at < arguments.size(); at++) {
      final CtExpression<?> argument = arguments.get(at);
      final CtTypeReference<?> parameter = parameters.get(at).getType();
      if (!hasOwnType(argument)
          || !argumentKnown(argument)
          || !known(parameter)
          || !ElementIds.erase(argument.getType(), caller.scope())
              .equals(ElementIds.erase(parameter, callee.scope()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasOwnType(CtExpression<?> argument) {
    return !(argument instanceof CtLiteral<?> literal && literal.getValue() == null)
        && !(argument instanceof CtLambda<?>)
        && !(argument instanceof CtExecutableReferenceExpression<?, ?>);
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
