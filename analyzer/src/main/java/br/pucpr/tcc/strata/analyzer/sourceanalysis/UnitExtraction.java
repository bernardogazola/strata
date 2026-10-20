package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.DeclaredType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.UnitElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

final class UnitExtraction {

  private final SourceRoot root;
  private final String relativePath;
  private final SimpleNameIndex index;
  private final boolean isTest;

  UnitExtraction(SourceRoot root, String relativePath, SimpleNameIndex index) {
    this.root = root;
    this.relativePath = relativePath;
    this.index = index;
    this.isTest = root.kind() == SourceRootKind.TEST;
  }

  UnitElements extract(CtCompilationUnit unit) {
    final Map<String, PackageElement> packages = new LinkedHashMap<>();
    final Map<String, SimpleNameIndex.Scope> scopes = new HashMap<>();
    final List<DeclaredType> types = new ArrayList<>();
    for (final CtType<?> top : unit.getDeclaredTypes()) {
      if (!ElementIds.isElementType(top)) {
        continue;
      }
      final String declaredPackage = ElementIds.packageName(top);
      packages.computeIfAbsent(declaredPackage, UnitExtraction::packageElement);
      final SimpleNameIndex.Scope scope =
          scopes.computeIfAbsent(declaredPackage, own -> index.scopeOf(unit, own));
      types.add(walk(top, scope));
    }
    return new UnitElements(relativePath, List.copyOf(packages.values()), types);
  }

  private DeclaredType walk(CtType<?> top, SimpleNameIndex.Scope scope) {
    final Map<CtType<?>, TypeBuilder> builders = new IdentityHashMap<>();
    for (final CtType<?> type : top.getElements(new TypeFilter<CtType<?>>(CtType.class))) {
      if (!ElementIds.isElementType(type)) {
        continue;
      }
      if (ElementIds.isModelLoss(type)) {
        throw new ModelLossException(type.getQualifiedName());
      }
      final TypeBuilder builder = new TypeBuilder(typeElement(type));
      builders.put(type, builder);
      final CtType<?> enclosing = type.getParent(CtType.class);
      if (enclosing != null) {
        builders.get(enclosing).nested().add(builder);
      }
    }
    for (final CtExecutable<?> executable :
        top.getElements(new TypeFilter<CtExecutable<?>>(CtExecutable.class))) {
      offer(executable, builders, scope);
    }
    return builders.get(top).build();
  }

  private void offer(
      CtExecutable<?> executable,
      Map<CtType<?>, TypeBuilder> builders,
      SimpleNameIndex.Scope scope) {
    final Optional<String> name = ElementIds.methodName(executable);
    final CtType<?> owner = ElementIds.methodOwner(executable);
    if (name.isEmpty() || owner == null) {
      return;
    }
    final TypeBuilder builder = builders.get(owner);
    if (builder != null) {
      final boolean own = owner == executable.getParent(CtType.class);
      builder.offer(methodElement(executable, name.get(), builder.type(), scope), own);
    }
  }

  private TypeElement typeElement(CtType<?> type) {
    return new TypeElement(
        CodeElementId.ofType(ElementIds.idName(type)),
        CodeElement.label(type.getSimpleName()),
        ElementIds.canonicalName(type),
        ElementIds.typeKind(type),
        CodeElementId.ofPackage(ElementIds.packageName(type)),
        relativePath,
        root.id(),
        ElementIds.modifiers(type),
        isTest,
        ElementIds.lines(type));
  }

  private MethodElement methodElement(
      CtExecutable<?> executable, String name, TypeElement owner, SimpleNameIndex.Scope scope) {
    for (final CtParameter<?> parameter : executable.getParameters()) {
      if (parameter.getType() == null) {
        Log.warn(
            relativePath
                + ": um parâmetro de "
                + owner.qualifiedName()
                + "#"
                + name
                + " não tem tipo no modelo; o id dele usa "
                + ElementIds.OBJECT);
      }
    }
    return new MethodElement(
        ElementIds.methodId(owner.id().sortKey(), name, executable, scope),
        CodeElement.label(name),
        owner.qualifiedName() + "#" + name,
        owner.id(),
        relativePath,
        root.id(),
        executable instanceof CtModifiable modifiable ? ElementIds.modifiers(modifiable) : Set.of(),
        isTest,
        ElementIds.lines(executable));
  }

  private static PackageElement packageElement(String qualifiedName) {
    return new PackageElement(
        CodeElementId.ofPackage(qualifiedName),
        CodeElement.label(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)),
        qualifiedName);
  }

  private record TypeBuilder(
      TypeElement type, Map<CodeElementId, Offered> methods, List<TypeBuilder> nested) {

    TypeBuilder(TypeElement type) {
      this(type, new LinkedHashMap<>(), new ArrayList<>());
    }

    void offer(MethodElement method, boolean own) {
      final Offered current = methods.get(method.id());
      if (current == null || (own && !current.own())) {
        methods.put(method.id(), new Offered(method, own));
      }
    }

    DeclaredType build() {
      return new DeclaredType(
          type,
          methods.values().stream().map(Offered::method).toList(),
          nested.stream().map(TypeBuilder::build).toList());
    }
  }

  private record Offered(MethodElement method, boolean own) {}
}
