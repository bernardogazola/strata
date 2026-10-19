package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.IdentityHashMap;
import java.util.Map;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;

final class SiteAttribution {

  private final SimpleNameIndex.Scope scope;
  private final Map<CtType<?>, CodeElementId> sources = new IdentityHashMap<>();
  private final Map<CtExecutable<?>, CodeElementId> methods = new IdentityHashMap<>();

  SiteAttribution(SimpleNameIndex.Scope scope) {
    this.scope = scope;
  }

  CodeElementId source(CtElement site) {
    final CtType<?> type = ElementIds.nearestElementType(site.getParent(CtType.class));
    if (type == null) {
      return null;
    }
    return sources.computeIfAbsent(type, owner -> CodeElementId.ofType(ElementIds.idName(owner)));
  }

  CodeElementId fromMethod(CtElement site) {
    for (CtExecutable<?> executable = site.getParent(CtExecutable.class);
        executable != null;
        executable = executable.getParent(CtExecutable.class)) {
      final CodeElementId method = method(executable);
      if (method != null) {
        return method;
      }
    }
    return null;
  }

  private CodeElementId method(CtExecutable<?> executable) {
    final CodeElementId known = methods.get(executable);
    if (known != null) {
      return known;
    }
    final CtType<?> owner = ElementIds.methodOwner(executable);
    if (owner == null) {
      return null;
    }
    final CodeElementId method =
        ElementIds.methodId(
            ElementIds.idName(owner),
            ElementIds.methodName(executable).orElseThrow(),
            executable,
            scope);
    methods.put(executable, method);
    return method;
  }

  static Anchor at(CtElement element) {
    final SourcePosition position = element.getPosition();
    return position.isValidPosition()
        ? new Anchor(position.getLine(), position.getSourceStart())
        : Anchor.NONE;
  }

  static Anchor call(CtInvocation<?> invocation) {
    final Anchor name =
        invocation.getExecutable() == null ? Anchor.NONE : at(invocation.getExecutable());
    return Anchor.NONE.equals(name) ? at(invocation) : name;
  }

  static Anchor nearest(CtElement element) {
    for (CtElement current = element; current != null; ) {
      final Anchor anchor = at(current);
      if (!Anchor.NONE.equals(anchor)) {
        return anchor;
      }
      current = current.isParentInitialized() ? current.getParent() : null;
    }
    return Anchor.NONE;
  }

  record Anchor(int line, int offset) {

    static final Anchor NONE = new Anchor(0, -1);
  }
}
