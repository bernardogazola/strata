package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IndexBuilder {

  private static final String OBJECT = "java.lang.Object";
  private static final String ENUM = "java.lang.Enum";
  private static final String RECORD = "java.lang.Record";
  private static final String ANNOTATION = "java.lang.annotation.Annotation";

  private final ExternalPackages platform;
  private final JavaLangTypes javaLang = new JavaLangTypes();
  private final Map<String, IndexedType> types = new HashMap<>();
  private final Map<IndexedType, Draft> drafts = new IdentityHashMap<>();
  private final List<Unit> units = new ArrayList<>();

  IndexBuilder(ExternalPackages platform) {
    this.platform = platform;
  }

  IndexBuilder add(UnitShape shape, Path file) {
    final ImportScope scope = ImportScope.of(shape);
    for (final UnitShape.TypeShape top : shape.types()) {
      addType(
          top,
          SimpleNameIndex.qualify(shape.packageName(), top.name()),
          shape.packageName(),
          scope,
          null);
    }
    units.add(new Unit(shape, file));
    return this;
  }

  IndexBuilder addDeclarations(Collection<SourceFile> files) {
    for (final SourceFile file : files) {
      final SourceFile.Declarations declarations = file.declarations();
      if (declarations == null) {
        continue;
      }
      for (final String name : declarations.topLevelTypes()) {
        final String id = SimpleNameIndex.qualify(declarations.packageName(), name);
        types.putIfAbsent(id, new IndexedType(id, name, declarations.packageName(), true, null));
      }
    }
    return this;
  }

  private void addType(
      UnitShape.TypeShape shape,
      String id,
      String packageName,
      ImportScope scope,
      Draft enclosing) {
    if (types.containsKey(id)) {
      return;
    }
    final IndexedType type =
        new IndexedType(id, shape.name(), packageName, enclosing == null, shape);
    types.put(id, type);
    final Draft draft = new Draft(type, shape, scope, enclosing);
    drafts.put(type, draft);
    for (final UnitShape.TypeShape member : shape.memberTypes()) {
      addType(member, id + "$" + member.name(), packageName, scope, draft);
    }
  }

  SimpleNameIndex build() {
    final SimpleNameIndex index = new SimpleNameIndex(types, retainedStatics(), platform);
    final List<IndexedType> ordered =
        types.values().stream()
            .sorted((left, right) -> CodeElementId.compareCodePoints(left.id(), right.id()))
            .toList();
    final Set<IndexedType> resolving = Collections.newSetFromMap(new IdentityHashMap<>());
    index.resolvingSupertypesWith(type -> resolveSupertypes(index, type, resolving));
    try {
      for (final IndexedType type : ordered) {
        index.supertypesOf(type);
      }
    } finally {
      index.resolvingSupertypesWith(null);
    }
    markComplete(ordered);
    markOpaque(ordered);
    for (final IndexedType type : ordered) {
      final Draft draft = drafts.get(type);
      if (draft != null) {
        type.fieldTypes(draft.fieldTypes(index));
      }
    }
    drafts.clear();
    units.clear();
    return index;
  }

  private String[] resolveSupertypes(
      SimpleNameIndex index, IndexedType type, Set<IndexedType> resolving) {
    final Draft draft = drafts.get(type);
    if (draft == null || !resolving.add(type)) {
      return SimpleNameIndex.NO_NAMES;
    }
    try {
      final UnitScope scope = new UnitScope(index, draft.scope());
      final List<String> outer = draft.outerChain();
      final List<String> resolved = new ArrayList<>();
      final UnitShape.TypeShape shape = draft.shape();
      final List<String> written = new ArrayList<>(shape.superInterfaces().size() + 1);
      if (shape.superclass() != null) {
        written.add(shape.superclass());
      }
      written.addAll(shape.superInterfaces());
      for (final String text : written) {
        final String name = scope.resolveSource(text, outer);
        resolved.add(name);
        type.opaque(type.opaque() || outsideJdk(text, name));
      }
      final String implicit = implicitSupertype(type, shape);
      if (implicit != null) {
        resolved.add(implicit);
      }
      final String[] supertypes = resolved.toArray(String[]::new);
      type.supertypes(supertypes);
      return supertypes;
    } finally {
      resolving.remove(type);
    }
  }

  private boolean outsideJdk(String text, String name) {
    if (name != null) {
      return !types.containsKey(name) && !SimpleNameIndex.isRoot(name) && !platform.isJdk(name);
    }
    if (text.indexOf('.') < 0) {
      return javaLang.qualify(text) == null;
    }
    for (int dot = text.lastIndexOf('.'); dot > 0; dot = text.lastIndexOf('.', dot - 1)) {
      if (platform.isJdkPackage(text.substring(0, dot))) {
        return false;
      }
    }
    return true;
  }

  private static String implicitSupertype(IndexedType type, UnitShape.TypeShape shape) {
    return switch (shape.kind()) {
      case ENUM -> ENUM;
      case RECORD -> RECORD;
      case ANNOTATION -> ANNOTATION;
      case INTERFACE -> null;
      case CLASS -> shape.superclass() == null && !OBJECT.equals(type.id()) ? OBJECT : null;
    };
  }

  private void markOpaque(List<IndexedType> ordered) {
    final Map<IndexedType, List<IndexedType>> heirs = new IdentityHashMap<>();
    final Deque<IndexedType> opaque = new ArrayDeque<>();
    for (final IndexedType type : ordered) {
      for (final String supertype : type.supertypes()) {
        final IndexedType parent = supertype == null ? null : types.get(supertype);
        if (parent != null) {
          heirs.computeIfAbsent(parent, _ -> new ArrayList<>()).add(type);
        }
      }
      if (type.opaque() || !type.structured()) {
        type.opaque(false);
        opaque.add(type);
      }
    }
    while (!opaque.isEmpty()) {
      final IndexedType type = opaque.poll();
      if (!type.opaque()) {
        type.opaque(true);
        opaque.addAll(heirs.getOrDefault(type, List.of()));
      }
    }
  }

  private void markComplete(List<IndexedType> ordered) {
    final Map<IndexedType, List<IndexedType>> heirs = new IdentityHashMap<>();
    final Deque<IndexedType> incomplete = new ArrayDeque<>();
    for (final IndexedType type : ordered) {
      type.complete(true);
      boolean open = !type.structured();
      for (final String supertype : type.supertypes()) {
        final IndexedType parent = supertype == null ? null : types.get(supertype);
        if (parent != null) {
          heirs.computeIfAbsent(parent, _ -> new ArrayList<>()).add(type);
        } else {
          open |= supertype == null || !SimpleNameIndex.isRoot(supertype);
        }
      }
      if (open) {
        incomplete.add(type);
      }
    }
    while (!incomplete.isEmpty()) {
      final IndexedType type = incomplete.poll();
      if (type.complete()) {
        type.complete(false);
        incomplete.addAll(heirs.getOrDefault(type, List.of()));
      }
    }
  }

  private Map<Path, List<String>> retainedStatics() {
    final SimpleNameIndex names = new SimpleNameIndex(types, Map.of(), platform);
    final Map<Path, List<String>> retained = new HashMap<>();
    for (final Unit unit : units) {
      if (unit.file() == null) {
        continue;
      }
      final List<String> droppable = new ArrayList<>();
      for (final String text : unit.shape().singleStaticImports()) {
        final int lastDot = text.lastIndexOf('.');
        if (lastDot > 0
            && inheritedOnly(names, text.substring(0, lastDot), text.substring(lastDot + 1))) {
          droppable.add(text);
        }
      }
      if (!droppable.isEmpty()) {
        retained.merge(unit.file(), List.copyOf(droppable), IndexBuilder::concat);
      }
    }
    return retained;
  }

  private static boolean inheritedOnly(SimpleNameIndex names, String owner, String member) {
    final IndexedType type = names.type(names.importedTypeName(owner));
    return type != null
        && type.structured()
        && type.methods().find(member) < 0
        && type.fields().find(member) < 0
        && names.type(type.id() + "$" + member) == null;
  }

  private static List<String> concat(List<String> left, List<String> right) {
    final List<String> both = new ArrayList<>(left);
    both.addAll(right);
    return List.copyOf(both);
  }

  private record Unit(UnitShape shape, Path file) {}

  private record Draft(
      IndexedType type, UnitShape.TypeShape shape, ImportScope scope, Draft enclosing) {

    List<String> outerChain() {
      final List<String> chain = new ArrayList<>();
      for (Draft current = enclosing; current != null; current = current.enclosing()) {
        chain.add(current.type().id());
      }
      return chain;
    }

    Set<String> typeVariables() {
      final Set<String> variables = new HashSet<>();
      for (Draft current = this; current != null; current = current.enclosing()) {
        variables.addAll(current.shape().typeParameters());
      }
      return variables;
    }

    String[] fieldTypes(SimpleNameIndex index) {
      final List<String> written = shape.fieldTypes();
      if (written.isEmpty()) {
        return SimpleNameIndex.NO_NAMES;
      }
      final UnitScope scope = new UnitScope(index, this.scope);
      final List<String> body = new ArrayList<>();
      body.add(type.id());
      body.addAll(outerChain());
      final Set<String> variables = typeVariables();
      final String[] resolved = new String[written.size()];
      for (int at = 0; at < resolved.length; at++) {
        resolved[at] = scope.fieldType(written.get(at), body, variables);
      }
      return resolved;
    }
  }
}
