package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Modifier;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import spoon.experimental.CtUnresolvedImport;
import spoon.reflect.code.CtLambda;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtFormalTypeDeclarer;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.support.reflect.CtExtendedModifier;

final class ElementIds {

  static final String OBJECT = "java.lang.Object";

  private static final String CONSTRUCTOR = "<init>";
  private static final String STATIC_INITIALIZER = "<clinit>";
  private static final String ON_DEMAND_SUFFIX = ".*";

  private static final Set<CtRole> HEADER_ROLES =
      EnumSet.of(
          CtRole.SUPER_TYPE,
          CtRole.INTERFACE,
          CtRole.TYPE_PARAMETER,
          CtRole.ANNOTATION,
          CtRole.PERMITTED_TYPE);

  private static final BinaryOperator<String> SMALLER =
      BinaryOperator.minBy(Comparator.naturalOrder());

  private ElementIds() {}

  static boolean isElementType(CtType<?> type) {
    if (type instanceof CtTypeParameter) {
      return false;
    }
    for (CtType<?> current = type; current != null; current = current.getParent(CtType.class)) {
      if (current.isAnonymous() || current.isLocalType()) {
        return false;
      }
    }
    return true;
  }

  static CtType<?> nearestElementType(CtType<?> type) {
    CtType<?> current = type;
    while (current != null && !isElementType(current)) {
      current = current.getParent(CtType.class);
    }
    return current;
  }

  static Optional<String> methodName(CtExecutable<?> executable) {
    return switch (executable) {
      case CtConstructor<?> _ -> Optional.of(CONSTRUCTOR);
      case CtAnonymousExecutable block ->
          block.isStatic() ? Optional.of(STATIC_INITIALIZER) : Optional.empty();
      case CtLambda<?> _ -> Optional.empty();
      case CtMethod<?> method -> Optional.of(method.getSimpleName());
      default -> Optional.empty();
    };
  }

  static CtType<?> methodOwner(CtExecutable<?> executable) {
    if (methodName(executable).isEmpty()) {
      return null;
    }
    final CtType<?> declaring = executable.getParent(CtType.class);
    if (declaring == null || isElementType(declaring)) {
      return declaring;
    }
    if (executable.isImplicit() || !(executable instanceof CtMethod<?>)) {
      return null;
    }
    return nearestElementType(declaring);
  }

  static CodeElementId methodId(
      String ownerIdName, String name, CtExecutable<?> executable, SimpleNameIndex.Scope scope) {
    final List<String> parameters = new ArrayList<>();
    for (final CtParameter<?> parameter : executable.getParameters()) {
      parameters.add(erase(parameter.getType(), scope));
    }
    return CodeElementId.ofMethod(ownerIdName, name, parameters);
  }

  static String erase(CtTypeReference<?> reference, SimpleNameIndex.Scope scope) {
    return erase(reference, scope, null);
  }

  static String erase(CtTypeReference<?> reference, UnaryOperator<String> canonical) {
    return erase(reference, (name, writtenSimple) -> canonical.apply(name), null);
  }

  private static String erase(
      CtTypeReference<?> reference, SimpleNameIndex.Scope scope, Set<CtTypeParameter> seen) {
    return switch (reference) {
      case null -> OBJECT;
      case CtArrayTypeReference<?> array -> erase(array.getComponentType(), scope, seen) + "[]";
      case CtIntersectionTypeReference<?> intersection ->
          intersection.getBounds().isEmpty()
              ? OBJECT
              : erase(intersection.getBounds().getFirst(), scope, seen);
      case CtWildcardReference _ -> OBJECT;
      case CtTypeParameterReference variable -> eraseVariable(variable, scope, seen);
      default -> resolveLeaf(reference, scope).name();
    };
  }

  static SimpleNameIndex.NameContext context(CtElement element) {
    final CtType<?> owner = bodyOwner(element);
    return owner == null ? SimpleNameIndex.NameContext.NONE : contextOf(owner);
  }

  static SimpleNameIndex.NameContext contextOf(CtType<?> start) {
    final List<String> chain = new ArrayList<>();
    final Map<String, List<SimpleNameIndex.WrittenName>> locals = new HashMap<>();
    for (CtType<?> type = start; type != null; type = type.getParent(CtType.class)) {
      if (isElementType(type)) {
        chain.add(idName(type));
      } else {
        final String key = type.getQualifiedName();
        chain.add(key);
        locals.put(key, writtenSupertypes(type));
      }
    }
    return chain.isEmpty()
        ? SimpleNameIndex.NameContext.NONE
        : new SimpleNameIndex.NameContext(chain, locals);
  }

  static SimpleNameIndex.NameResolution resolveLeaf(
      CtTypeReference<?> leaf, SimpleNameIndex.Scope scope) {
    final String name = leaf.getQualifiedName();
    if (hasLocalSegment(name)) {
      return new SimpleNameIndex.NameResolution(
          stripLeadingDigits(leaf.getSimpleName()), SimpleNameIndex.Outcome.UNCHANGED_INCOMPLETE);
    }
    return scope.resolve(name, isWrittenSimple(leaf), scope.context(leaf));
  }

  static CtType<?> bodyOwner(CtElement element) {
    if (element instanceof CtType<?> type && !(element instanceof CtTypeParameter)) {
      return type;
    }
    CtElement child = element;
    while (child.isParentInitialized()) {
      final CtElement parent = child.getParent();
      if (parent == null) {
        return null;
      }
      if (parent instanceof CtType<?> type && !(parent instanceof CtTypeParameter)) {
        return isHeader(child) ? type.getParent(CtType.class) : type;
      }
      child = parent;
    }
    return null;
  }

  private static boolean isHeader(CtElement child) {
    final boolean candidate =
        child instanceof CtTypeReference<?>
            || child instanceof CtTypeParameter
            || child instanceof CtAnnotation<?>;
    return candidate && HEADER_ROLES.contains(child.getRoleInParent());
  }

  private static List<SimpleNameIndex.WrittenName> writtenSupertypes(CtType<?> type) {
    final List<SimpleNameIndex.WrittenName> supertypes = new ArrayList<>();
    final CtTypeReference<?> superclass = type.getSuperclass();
    if (superclass != null) {
      supertypes.add(written(superclass));
    }
    for (final CtTypeReference<?> superinterface : type.getSuperInterfaces()) {
      supertypes.add(written(superinterface));
    }
    return supertypes;
  }

  private static SimpleNameIndex.WrittenName written(CtTypeReference<?> reference) {
    return new SimpleNameIndex.WrittenName(
        reference.getQualifiedName(), isWrittenSimple(reference));
  }

  static boolean isWrittenSimple(CtTypeReference<?> reference) {
    final CtPackageReference completed = reference.getPackage();
    return reference.getDeclaringType() == null && completed != null && completed.isImplicit();
  }

  private static String eraseVariable(
      CtTypeParameterReference variable, SimpleNameIndex.Scope scope, Set<CtTypeParameter> seen) {
    final CtTypeParameter declaration = variable.getDeclaration();
    if (declaration == null || declaration.getSuperclass() == null) {
      return OBJECT;
    }
    final Set<CtTypeParameter> path =
        seen == null ? Collections.newSetFromMap(new IdentityHashMap<>()) : seen;
    if (!path.add(declaration)) {
      return OBJECT;
    }
    return erase(declaration.getSuperclass(), scope, path);
  }

  static boolean hasLocalSegment(String name) {
    return localSegmentStart(name) >= 0;
  }

  static int localSegmentStart(String name) {
    for (int dollar = name.indexOf('$'); dollar >= 0; dollar = name.indexOf('$', dollar + 1)) {
      if (dollar + 1 < name.length() && Character.isDigit(name.charAt(dollar + 1))) {
        return dollar;
      }
    }
    return -1;
  }

  private static String stripLeadingDigits(String simpleName) {
    int start = 0;
    while (start < simpleName.length() && Character.isDigit(simpleName.charAt(start))) {
      start++;
    }
    return simpleName.substring(start);
  }

  static TypeKind typeKind(CtType<?> type) {
    return switch (type) {
      case CtAnnotationType<?> _ -> TypeKind.ANNOTATION;
      case CtInterface<?> _ -> TypeKind.INTERFACE;
      case CtRecord _ -> TypeKind.RECORD;
      case CtEnum<?> _ -> TypeKind.ENUM;
      default -> TypeKind.CLASS;
    };
  }

  static Set<Modifier> modifiers(CtModifiable element) {
    final Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    for (final CtExtendedModifier modifier : element.getExtendedModifiers()) {
      if (!modifier.isImplicit()) {
        modifiers.add(modifier(modifier.getKind()));
      }
    }
    if (element instanceof CtMethod<?> method && method.isDefaultMethod()) {
      modifiers.add(Modifier.DEFAULT);
    }
    return modifiers;
  }

  private static Modifier modifier(ModifierKind kind) {
    return switch (kind) {
      case PUBLIC -> Modifier.PUBLIC;
      case PROTECTED -> Modifier.PROTECTED;
      case PRIVATE -> Modifier.PRIVATE;
      case ABSTRACT -> Modifier.ABSTRACT;
      case STATIC -> Modifier.STATIC;
      case SEALED -> Modifier.SEALED;
      case NON_SEALED -> Modifier.NON_SEALED;
      case FINAL -> Modifier.FINAL;
      case TRANSIENT -> Modifier.TRANSIENT;
      case VOLATILE -> Modifier.VOLATILE;
      case SYNCHRONIZED -> Modifier.SYNCHRONIZED;
      case NATIVE -> Modifier.NATIVE;
      case STRICTFP -> Modifier.STRICTFP;
    };
  }

  static SourceLines lines(CtElement element) {
    final SourcePosition position = element.getPosition();
    if (!position.isValidPosition()) {
      return SourceLines.UNAVAILABLE;
    }
    int start = position.getLine();
    for (final CtAnnotation<?> annotation : element.getAnnotations()) {
      start = Math.min(start, lineOf(annotation.getPosition(), start));
    }
    if (element instanceof CtModifiable modifiable) {
      for (final CtExtendedModifier modifier : modifiable.getExtendedModifiers()) {
        if (!modifier.isImplicit()) {
          start = Math.min(start, lineOf(modifier.getPosition(), start));
        }
      }
    }
    if (element instanceof CtFormalTypeDeclarer declarer) {
      for (final CtTypeParameter parameter : declarer.getFormalCtTypeParameters()) {
        start = Math.min(start, lineOf(parameter.getPosition(), start));
      }
    }
    if (element instanceof CtMethod<?> method && method.getType() != null) {
      start = Math.min(start, lineOf(method.getType().getPosition(), start));
    }
    return new SourceLines(start, Math.max(start, position.getEndLine()));
  }

  private static int lineOf(SourcePosition position, int fallback) {
    return position != null && position.isValidPosition() ? position.getLine() : fallback;
  }

  static String packageName(CtType<?> type) {
    final CtPackage declared = type.getPackage();
    return declared == null || declared.isUnnamedPackage() ? "" : declared.getQualifiedName();
  }

  static String idName(CtType<?> type) {
    return qualified(type, "$");
  }

  static String canonicalName(CtType<?> type) {
    return qualified(type, ".");
  }

  private static String qualified(CtType<?> type, String nestingSeparator) {
    final Deque<String> names = new ArrayDeque<>();
    for (CtType<?> current = type; current != null; current = current.getParent(CtType.class)) {
      names.addFirst(current.getSimpleName());
    }
    final String nested = String.join(nestingSeparator, names);
    final String declaredPackage = packageName(type);
    return declaredPackage.isEmpty() ? nested : declaredPackage + "." + nested;
  }

  static boolean isModelLoss(CtType<?> type) {
    return !type.isImplicit() && !type.getPosition().isValidPosition();
  }

  static Set<String> onDemandPackages(CtCompilationUnit unit) {
    final Set<String> packages = new TreeSet<>();
    for (final CtImport declaration : unit.getImports()) {
      if (declaration instanceof CtUnresolvedImport unresolved) {
        final String text = unresolved.getUnresolvedReference();
        if (!unresolved.isStatic() && text != null && text.endsWith(ON_DEMAND_SUFFIX)) {
          packages.add(text.substring(0, text.length() - ON_DEMAND_SUFFIX.length()));
        }
      } else if (declaration.getImportKind() == CtImportKind.ALL_TYPES
          && declaration.getReference() instanceof CtPackageReference imported) {
        packages.add(imported.getQualifiedName());
      }
    }
    return packages;
  }

  static Map<String, String> singleTypeImports(CtCompilationUnit unit) {
    final Map<String, String> imports = new HashMap<>();
    for (final CtImport declaration : unit.getImports()) {
      if (declaration instanceof CtUnresolvedImport unresolved) {
        final String text = unresolved.getUnresolvedReference();
        if (!unresolved.isStatic() && text != null && !text.endsWith(ON_DEMAND_SUFFIX)) {
          imports.merge(text.substring(text.lastIndexOf('.') + 1), text, SMALLER);
        }
      } else if (declaration.getImportKind() == CtImportKind.TYPE
          && declaration.getReference() instanceof CtTypeReference<?> imported) {
        imports.merge(imported.getSimpleName(), imported.getQualifiedName(), SMALLER);
      }
    }
    return imports;
  }
}
