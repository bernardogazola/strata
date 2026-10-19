package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;

final class SimpleNameIndex {

  private static final Set<String> ROOTS =
      Set.of(
          "java.lang.Object",
          "java.lang.Enum",
          "java.lang.Record",
          "java.lang.annotation.Annotation");

  static final String[] NO_NAMES = new String[0];

  private final Map<String, IndexedType> types;

  private final Map<String, String[]> topLevelBySimpleName;

  private final Set<String> packages;

  private final Set<String> memberTypeNames;

  private final Map<Path, List<String>> retainedStatics;

  private final ExternalPackages platform;

  private Function<IndexedType, String[]> pendingSupertypes;

  SimpleNameIndex(
      Map<String, IndexedType> types,
      Map<Path, List<String>> retainedStatics,
      ExternalPackages platform) {
    this.types = Collections.unmodifiableMap(new HashMap<>(types));
    this.retainedStatics = Map.copyOf(retainedStatics);
    this.platform = platform;
    final Map<String, List<String>> bySimpleName = new HashMap<>();
    final Set<String> declaredPackages = new HashSet<>();
    final Set<String> memberNames = new HashSet<>();
    for (final IndexedType type : this.types.values()) {
      declaredPackages.add(type.packageName());
      if (type.isTopLevel()) {
        bySimpleName.computeIfAbsent(type.simpleName(), _ -> new ArrayList<>()).add(type.id());
      } else {
        memberNames.add(type.simpleName());
      }
    }
    this.memberTypeNames = Set.copyOf(memberNames);
    final Map<String, String[]> sorted = new HashMap<>();
    bySimpleName.forEach(
        (simpleName, ids) ->
            sorted.put(
                simpleName,
                ids.stream().sorted(CodeElementId::compareCodePoints).toArray(String[]::new)));
    this.topLevelBySimpleName = Map.copyOf(sorted);
    this.packages = Set.copyOf(declaredPackages);
  }

  static SimpleNameIndex of(List<SourceFile> files) {
    return builder().addDeclarations(files).build();
  }

  static IndexBuilder builder() {
    return new IndexBuilder(Platform.PACKAGES);
  }

  static Set<String> topLevelTypes(Collection<SourceFile> files) {
    final Set<String> topLevel = new HashSet<>();
    for (final SourceFile file : files) {
      final SourceFile.Declarations declarations = file.declarations();
      if (declarations != null) {
        for (final String type : declarations.topLevelTypes()) {
          topLevel.add(qualify(declarations.packageName(), type));
        }
      }
    }
    return topLevel;
  }

  Optional<String> declaredTopLevel(String idName) {
    if (isTopLevel(idName)) {
      return Optional.of(types.get(idName).id());
    }
    final int lastDot = idName.lastIndexOf('.');
    for (int dollar = idName.lastIndexOf('$');
        dollar > lastDot + 1;
        dollar = idName.lastIndexOf('$', dollar - 1)) {
      final String prefix = idName.substring(0, dollar);
      if (isTopLevel(prefix)) {
        return Optional.of(types.get(prefix).id());
      }
    }
    return Optional.empty();
  }

  Scope scope(
      String ownPackage, Set<String> onDemandPackages, Map<String, String> singleTypeImports) {
    return new UnitScope(this, ImportScope.of(ownPackage, onDemandPackages, singleTypeImports));
  }

  String canonicalize(String name, String ownPackage, Set<String> onDemandPackages) {
    return canonicalize(name, ownPackage, onDemandPackages, Map.of());
  }

  String canonicalize(
      String name,
      String ownPackage,
      Set<String> onDemandPackages,
      Map<String, String> singleTypeImports) {
    return scope(ownPackage, onDemandPackages, singleTypeImports).canonical(name, false);
  }

  Scope scopeOf(CtCompilationUnit unit, String ownPackage) {
    Objects.requireNonNull(unit, "unit");
    final List<String> retained =
        unit.getFile() == null
            ? List.of()
            : retainedStatics.getOrDefault(unit.getFile().toPath(), List.of());
    return new UnitScope(this, ImportScope.of(unit, ownPackage, retained));
  }

  List<String> retainedStatics(CtCompilationUnit unit) {
    return unit.getFile() == null
        ? List.of()
        : retainedStatics.getOrDefault(unit.getFile().toPath(), List.of());
  }

  List<String> topLevelCandidates(String simpleName, String fromPackage) {
    final List<String> candidates = new ArrayList<>();
    for (final String id : topLevelBySimpleName.getOrDefault(simpleName, NO_NAMES)) {
      final IndexedType type = types.get(id);
      final boolean defaultPackage = type.packageName().isEmpty();
      final boolean accessible =
          type.visibility() == MemberTable.PUBLIC || type.packageName().equals(fromPackage);
      if (accessible && !(defaultPackage && !fromPackage.isEmpty())) {
        candidates.add(id);
      }
    }
    return List.copyOf(candidates);
  }

  MemberAnswer hasMethod(String typeIdName, String methodName) {
    final IndexedType type = types.get(typeIdName);
    if (type == null || !type.structured()) {
      return MemberAnswer.UNKNOWN;
    }
    if (type.methods().find(methodName) >= 0) {
      return MemberAnswer.YES;
    }
    return new InheritedMembers(this).method(type, methodName, type.packageName());
  }

  FieldLookup field(String typeIdName, String fieldName) {
    final IndexedType type = types.get(typeIdName);
    if (type == null || !type.structured()) {
      return FieldLookup.UNKNOWN;
    }
    final int at = type.fields().find(fieldName);
    if (at >= 0) {
      return new FieldLookup(MemberAnswer.YES, type.id(), type.fieldTypes()[at]);
    }
    return new InheritedMembers(this).field(type, fieldName, type.packageName());
  }

  MemberTypeLookup memberType(String typeIdName, String simpleName) {
    final TypeLookup lookup = memberTypeOf(typeIdName, simpleName, false);
    return switch (lookup.state()) {
      case FOUND -> new MemberTypeLookup(MemberAnswer.YES, lookup.name(), false);
      case AMBIGUOUS -> new MemberTypeLookup(MemberAnswer.UNKNOWN, null, true);
      case UNKNOWN -> new MemberTypeLookup(MemberAnswer.UNKNOWN, null, false);
      case NONE -> new MemberTypeLookup(MemberAnswer.NO, null, false);
    };
  }

  int size() {
    return types.size();
  }

  int retainedStaticImports() {
    return retainedStatics.values().stream().mapToInt(List::size).sum();
  }

  IndexedType type(String id) {
    return types.get(id);
  }

  boolean isTopLevel(String name) {
    final IndexedType type = types.get(name);
    return type != null && type.isTopLevel();
  }

  boolean declaresPackage(String packageName) {
    return packages.contains(packageName);
  }

  String importedTypeName(String text) {
    final int top = topLevelPrefixEnd(text);
    if (top > 0) {
      final String rest = text.substring(top).replace('.', '$');
      return canonicalInstance(types.get(text.substring(0, top)).id() + rest);
    }
    for (int dot = text.lastIndexOf('.'); dot > 0; dot = text.lastIndexOf('.', dot - 1)) {
      if (platform.isJdkPackage(text.substring(0, dot))) {
        return text.substring(0, dot + 1) + text.substring(dot + 1).replace('.', '$');
      }
    }
    return text;
  }

  int topLevelPrefixEnd(String name) {
    final int firstDot = name.indexOf('.');
    if (firstDot < 0) {
      return -1;
    }
    if (isTopLevel(name)) {
      return name.length();
    }
    for (int end = name.length() - 1; end > firstDot; end--) {
      final char separator = name.charAt(end);
      if ((separator == '.' || separator == '$') && isTopLevel(name.substring(0, end))) {
        return end;
      }
    }
    return -1;
  }

  String canonicalInstance(String name) {
    final IndexedType type = types.get(name);
    return type == null ? name : type.id();
  }

  static boolean isRoot(String name) {
    return ROOTS.contains(name);
  }

  TypeLookup memberTypeOf(String ownerId, String name, boolean staticOnly) {
    final IndexedType owner = types.get(ownerId);
    if (owner == null || !owner.structured()) {
      return TypeLookup.UNKNOWN;
    }
    if (pendingSupertypes == null && !memberTypeNames.contains(name)) {
      return owner.complete() ? TypeLookup.NONE : TypeLookup.UNKNOWN;
    }
    final IndexedType declared = types.get(owner.id() + "$" + name);
    if (declared != null) {
      return staticOnly && !declared.isStatic() ? TypeLookup.NONE : TypeLookup.found(declared.id());
    }
    return new InheritedMembers(this)
        .memberType(supertypesOf(owner), name, staticOnly, owner.packageName());
  }

  TypeLookup inheritedMemberType(
      String[] supertypes, String name, boolean staticOnly, String heirPackage) {
    return new InheritedMembers(this).memberType(supertypes, name, staticOnly, heirPackage);
  }

  String declaredMemberType(IndexedType owner, String name, String fromPackage) {
    final IndexedType declared = types.get(owner.id() + "$" + name);
    if (declared == null || declared.visibility() == MemberTable.PRIVATE) {
      return null;
    }
    final boolean samePackage = declared.packageName().equals(fromPackage);
    return declared.visibility() == MemberTable.PACKAGE && !samePackage ? null : declared.id();
  }

  void resolvingSupertypesWith(Function<IndexedType, String[]> pending) {
    pendingSupertypes = pending;
  }

  String[] supertypesOf(IndexedType type) {
    final String[] supertypes = type.supertypes();
    if (supertypes != null) {
      return supertypes;
    }
    return pendingSupertypes == null ? NO_NAMES : pendingSupertypes.apply(type);
  }

  static String qualify(String packageName, String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  private static final class Platform {

    private static final ExternalPackages PACKAGES = ExternalPackages.load();

    private Platform() {}
  }

  enum MemberAnswer {
    YES,
    NO,
    UNKNOWN
  }

  record FieldLookup(MemberAnswer answer, String declaringType, String typeName) {

    static final FieldLookup NO = new FieldLookup(MemberAnswer.NO, null, null);
    static final FieldLookup UNKNOWN = new FieldLookup(MemberAnswer.UNKNOWN, null, null);
  }

  record MemberTypeLookup(MemberAnswer answer, String typeName, boolean ambiguous) {}

  record TypeLookup(LookupState state, String name) {

    static final TypeLookup NONE = new TypeLookup(LookupState.NONE, null);
    static final TypeLookup UNKNOWN = new TypeLookup(LookupState.UNKNOWN, null);
    static final TypeLookup AMBIGUOUS = new TypeLookup(LookupState.AMBIGUOUS, null);

    static TypeLookup found(String name) {
      return new TypeLookup(LookupState.FOUND, name);
    }
  }

  enum LookupState {
    FOUND,
    AMBIGUOUS,
    NONE,
    UNKNOWN
  }

  record NameContext(List<String> enclosingTypes, Map<String, List<WrittenName>> localSupertypes) {

    static final NameContext NONE = new NameContext(List.of());

    NameContext {
      enclosingTypes = List.copyOf(enclosingTypes);
      localSupertypes = Map.copyOf(localSupertypes);
    }

    NameContext(List<String> enclosingTypes) {
      this(enclosingTypes, Map.of());
    }
  }

  record WrittenName(String name, boolean writtenSimple) {

    WrittenName {
      Objects.requireNonNull(name, "name");
    }
  }

  record NameResolution(String name, Outcome outcome) {}

  enum Outcome {
    PLACED,
    UNCHANGED_COMPLETE,
    UNCHANGED_INCOMPLETE,
    AMBIGUOUS
  }

  @FunctionalInterface
  interface Scope {

    String canonical(String name, boolean writtenSimple);

    default NameResolution resolve(String name, boolean writtenSimple, NameContext context) {
      final String canonical = canonical(name, writtenSimple);
      return new NameResolution(
          canonical, canonical.equals(name) ? Outcome.UNCHANGED_INCOMPLETE : Outcome.PLACED);
    }

    default NameContext context(CtElement element) {
      return ElementIds.context(element);
    }
  }
}
