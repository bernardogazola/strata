package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.DeclaredType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.UnitElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
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
    final Set<String> onDemand = ElementIds.onDemandPackages(unit);
    final Map<String, PackageElement> packages = new LinkedHashMap<>();
    final List<DeclaredType> types = new ArrayList<>();
    for (final CtType<?> top : unit.getDeclaredTypes()) {
      if (!ElementIds.isElementType(top)) {
        continue;
      }
      final String declaredPackage = ElementIds.packageName(top);
      packages.computeIfAbsent(declaredPackage, UnitExtraction::packageElement);
      types.add(walk(top, index.scope(declaredPackage, onDemand)));
    }
    return new UnitElements(relativePath, List.copyOf(packages.values()), types);
  }

  private DeclaredType walk(CtType<?> top, UnaryOperator<String> canonical) {
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
      offer(executable, builders, canonical);
    }
    return builders.get(top).build();
  }

  private void offer(
      CtExecutable<?> executable,
      Map<CtType<?>, TypeBuilder> builders,
      UnaryOperator<String> canonical) {
    final Optional<String> name = ElementIds.methodName(executable);
    if (name.isEmpty()) {
      return;
    }
    final CtType<?> declaring = executable.getParent(CtType.class);
    final TypeBuilder own = builders.get(declaring);
    if (own != null) {
      own.offer(methodElement(executable, name.get(), own.type(), canonical), true);
      return;
    }
    if (executable.isImplicit() || !(executable instanceof CtMethod<?>)) {
      return;
    }
    final TypeBuilder owner = builders.get(ElementIds.nearestElementType(declaring));
    if (owner != null) {
      owner.offer(methodElement(executable, name.get(), owner.type(), canonical), false);
    }
  }

  private TypeElement typeElement(CtType<?> type) {
    return new TypeElement(
        CodeElementId.ofType(ElementIds.idName(type)),
        ElementIds.label(type.getSimpleName()),
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
      CtExecutable<?> executable, String name, TypeElement owner, UnaryOperator<String> canonical) {
    final List<String> parameters = new ArrayList<>();
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
      parameters.add(ElementIds.erase(parameter.getType(), canonical));
    }
    return new MethodElement(
        CodeElementId.ofMethod(owner.id().sortKey(), name, parameters),
        ElementIds.label(name),
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
        ElementIds.label(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)),
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
