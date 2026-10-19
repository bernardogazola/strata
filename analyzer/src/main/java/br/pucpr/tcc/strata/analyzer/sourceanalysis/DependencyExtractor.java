package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtModuleRequirement;
import spoon.reflect.declaration.CtModuleRequirement.RequiresModifier;
import spoon.reflect.declaration.CtType;

final class DependencyExtractor {

  private final SimpleNameIndex index;
  private final Set<String> batchTopLevelTypes;

  DependencyExtractor(SimpleNameIndex index, Set<String> batchTopLevelTypes) {
    this.index = Objects.requireNonNull(index, "index");
    this.batchTopLevelTypes = Set.copyOf(batchTopLevelTypes);
  }

  UnitDependencies extract(String relativePath, CtCompilationUnit unit) {
    if (unit.getUnitType() == CtCompilationUnit.UNIT_TYPE.MODULE_DECLARATION) {
      return new UnitDependencies(relativePath, List.of(), modules(relativePath, unit), Map.of());
    }
    final Map<CodeElementId, Map<DropReason, Integer>> drops = new HashMap<>();
    final EvidenceCollector collector = new EvidenceCollector(relativePath);
    final Set<String> onDemand = ElementIds.onDemandPackages(unit);
    final Map<String, String> singleTypeImports = ElementIds.singleTypeImports(unit);
    final List<CtImport> imports = unit.getImports();
    final CtType<?> importOwner = ImportSites.owner(relativePath, unit);
    final Set<Long> fieldDeclarations = new HashSet<>();
    for (final CtType<?> top : unit.getDeclaredTypes()) {
      if (!ElementIds.isElementType(top)) {
        continue;
      }
      final Map<DropReason, Integer> topDrops = new EnumMap<>(DropReason.class);
      final String ownPackage = ElementIds.packageName(top);
      final SimpleNameIndex.Scope scope = index.scope(ownPackage, onDemand, singleTypeImports);
      final TargetNames names =
          new TargetNames(
              index, batchTopLevelTypes, ownPackage, singleTypeImports, scope, topDrops);
      final OccurrenceRecorder recorder =
          new OccurrenceRecorder(collector, new SiteAttribution(scope), names);
      new DependencySites(recorder, ImportSites.staticMembers(imports, names), fieldDeclarations)
          .scan(top);
      if (top == importOwner) {
        ImportSites.record(imports, top, recorder);
      }
      if (!topDrops.isEmpty()) {
        drops.put(CodeElementId.ofType(ElementIds.idName(top)), topDrops);
      }
    }
    return new UnitDependencies(relativePath, collector.collect(), List.of(), drops);
  }

  private static List<ModuleDependency> modules(String relativePath, CtCompilationUnit unit) {
    final CtModule module = unit.getDeclaredModule();
    if (module == null) {
      return List.of();
    }
    final Map<String, ModuleDependency> required = new LinkedHashMap<>();
    for (final CtModuleRequirement requirement : module.getRequiredModules()) {
      if (requirement.getModuleReference() == null) {
        continue;
      }
      final String name = requirement.getModuleReference().getSimpleName();
      final Set<RequiresModifier> modifiers = requirement.getRequiresModifiers();
      required.putIfAbsent(
          name,
          new ModuleDependency(
              module.getSimpleName(),
              name,
              modifiers.contains(RequiresModifier.TRANSITIVE),
              modifiers.contains(RequiresModifier.STATIC),
              relativePath,
              SiteAttribution.at(requirement).line()));
    }
    final List<ModuleDependency> modules = new ArrayList<>(required.values());
    modules.sort(Comparator.comparingInt(ModuleDependency::line));
    return List.copyOf(modules);
  }

  record UnitDependencies(
      String relativePath,
      List<ExtractedDependency> dependencies,
      List<ModuleDependency> modules,
      Map<CodeElementId, Map<DropReason, Integer>> drops) {

    UnitDependencies {
      Objects.requireNonNull(relativePath, "relativePath");
      dependencies = List.copyOf(dependencies);
      modules = List.copyOf(modules);
      final Map<CodeElementId, Map<DropReason, Integer>> byTop = new HashMap<>();
      drops.forEach(
          (top, reasons) -> {
            final Map<DropReason, Integer> ordered = new EnumMap<>(DropReason.class);
            ordered.putAll(reasons);
            byTop.put(top, Collections.unmodifiableMap(ordered));
          });
      drops = Collections.unmodifiableMap(byTop);
    }
  }
}
