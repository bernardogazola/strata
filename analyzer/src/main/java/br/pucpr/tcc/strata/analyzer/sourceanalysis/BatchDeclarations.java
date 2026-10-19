package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;

final class BatchDeclarations {

  private final SimpleNameIndex index;
  private final Set<String> batchTopLevelTypes;
  private final Map<String, Optional<CtType<?>>> types = new HashMap<>();
  private final Map<CtExecutable<?>, Optional<CodeElementId>> calleeIds = new IdentityHashMap<>();

  BatchDeclarations(SimpleNameIndex index, Set<String> batchTopLevelTypes) {
    this.index = index;
    this.batchTopLevelTypes = batchTopLevelTypes;
  }

  Set<String> batchTopLevelTypes() {
    return batchTopLevelTypes;
  }

  boolean inBatch(String idName) {
    final Optional<String> topLevel = index.declaredTopLevel(idName);
    return topLevel.isPresent() && batchTopLevelTypes.contains(topLevel.get());
  }

  CtExecutable<?> executable(CtExecutableReference<?> reference) {
    final CtType<?> type = reference == null ? null : type(reference.getDeclaringType());
    if (type == null) {
      return null;
    }
    final CtTypeReference<?>[] parameters =
        reference.getParameters().toArray(new CtTypeReference<?>[0]);
    for (final CtTypeReference<?> parameter : parameters) {
      if (parameter == null) {
        return null;
      }
    }
    final CtExecutable<?> method = type.getMethod(reference.getSimpleName(), parameters);
    if (method != null) {
      return method;
    }
    return reference.isConstructor() && type instanceof CtClass<?> type1
        ? type1.getConstructor(parameters)
        : null;
  }

  CtField<?> field(CtFieldReference<?> reference) {
    final CtType<?> type = reference == null ? null : type(reference.getDeclaringType());
    if (type == null) {
      return null;
    }
    final CtField<?> field = type.getField(reference.getSimpleName());
    if (field != null) {
      return field;
    }
    return type instanceof CtEnum<?> enumeration
        ? enumeration.getEnumValue(reference.getSimpleName())
        : null;
  }

  CodeElementId calleeId(
      CtExecutable<?> declaration, Function<CtExecutable<?>, CodeElementId> compute) {
    return calleeIds
        .computeIfAbsent(declaration, key -> Optional.ofNullable(compute.apply(key)))
        .orElse(null);
  }

  CtType<?> type(CtTypeReference<?> reference) {
    if (reference == null) {
      return null;
    }
    final String qualified = reference.getQualifiedName();
    if (!inBatch(qualified)) {
      return null;
    }
    final CtType<?> type = lookup(qualified, reference.getFactory()).orElse(null);
    return type != null && type.getPosition().isValidPosition() ? type : null;
  }

  private Optional<CtType<?>> lookup(String qualified, Factory factory) {
    final Optional<CtType<?>> known = types.get(qualified);
    if (known != null) {
      return known;
    }
    final Optional<CtType<?>> found = Optional.ofNullable(resolve(qualified, factory));
    types.put(qualified, found);
    return found;
  }

  private CtType<?> resolve(String qualified, Factory factory) {
    final int lastDot = qualified.lastIndexOf('.');
    final CtPackage declaredPackage =
        lastDot > 0
            ? factory.Package().get(qualified.substring(0, lastDot))
            : factory.Package().getRootPackage();
    if (declaredPackage != null) {
      final CtType<?> topLevel = declaredPackage.getType(qualified.substring(lastDot + 1));
      if (topLevel != null) {
        return topLevel;
      }
    }
    final int dollar = qualified.lastIndexOf('$');
    if (dollar <= lastDot + 1) {
      return null;
    }
    final String simpleName = qualified.substring(dollar + 1);
    if (!simpleName.isEmpty() && Character.isJavaIdentifierStart(simpleName.charAt(0))) {
      final CtType<?> outer = lookup(qualified.substring(0, dollar), factory).orElse(null);
      if (outer == null) {
        return null;
      }
      if (!outer.isLocalType()) {
        final CtType<?> nested = directMember(outer.getNestedTypes(), simpleName);
        if (nested != null) {
          return nested;
        }
      }
    }
    return factory.Type().get(qualified);
  }

  private static CtType<?> directMember(Set<CtType<?>> members, String simpleName) {
    for (final CtType<?> member : members) {
      if (simpleName.equals(member.getSimpleName())) {
        return member;
      }
    }
    return null;
  }

  static List<? extends CtExecutable<?>> sameName(CtExecutable<?> declaration) {
    final CtType<?> declaring = declaration.getParent(CtType.class);
    if (declaring == null) {
      return List.of(declaration);
    }
    if (declaration instanceof CtConstructor<?> && declaring instanceof CtClass<?> type) {
      return List.copyOf(type.getConstructors());
    }
    return declaring.getMethodsByName(declaration.getSimpleName());
  }
}
