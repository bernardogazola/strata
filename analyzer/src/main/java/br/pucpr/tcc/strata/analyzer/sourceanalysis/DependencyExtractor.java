package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
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
  private final BatchDeclarations declarations;
  private final TargetNames.Rules rules;

  DependencyExtractor(SimpleNameIndex index, Set<String> batchTopLevelTypes) {
    this(index, batchTopLevelTypes, new JavaLangTypes());
  }

  DependencyExtractor(
      SimpleNameIndex index, Set<String> batchTopLevelTypes, JavaLangTypes javaLang) {
    this.index = Objects.requireNonNull(index, "index");
    this.rules =
        TargetNames.Rules.of(
            index, batchTopLevelTypes, Objects.requireNonNull(javaLang, "javaLang"));
    this.declarations = rules.declarations();
  }

  UnitDependencies extract(String relativePath, CtCompilationUnit unit) {
    if (unit.getUnitType() == CtCompilationUnit.UNIT_TYPE.MODULE_DECLARATION) {
      return new UnitDependencies(
          relativePath, List.of(), modules(relativePath, unit), Map.of(), Map.of(), 0);
    }
    final List<CtType<?>> tops = elementTypes(unit);
    if (tops.isEmpty()) {
      return new UnitDependencies(relativePath, List.of(), List.of(), Map.of(), Map.of(), 0);
    }
    final Map<CodeElementId, Map<DropReason, Integer>> drops = new HashMap<>();
    final EvidenceCollector collector = new EvidenceCollector(relativePath);
    final UnitHomes homes = new UnitHomes(index);
    final Home home = homes.of(unit, ElementIds.packageName(tops.getFirst()));
    final SiteAttribution attribution = new SiteAttribution(home.scope());
    final CalleeIds callees = new CalleeIds(declarations, homes, rules.platform());
    final UnqualifiedCalls unqualified = new UnqualifiedCalls(index, home);
    final List<CtImport> imports = unit.getImports();
    final CtType<?> importOwner = ImportSites.owner(relativePath, unit);
    final Set<Long> fieldDeclarations = new HashSet<>();
    for (final CtType<?> top : tops) {
      final Map<DropReason, Integer> topDrops = new EnumMap<>(DropReason.class);
      final TargetNames names = new TargetNames(rules, home, topDrops);
      final OccurrenceRecorder recorder =
          new OccurrenceRecorder(collector, attribution, names, callees);
      final CallTargets calls = new CallTargets(names, unqualified, declarations, homes);
      new DependencySites(recorder, calls, fieldDeclarations).scan(top);
      if (top == importOwner) {
        ImportSites.record(imports, top, recorder);
        ImportSites.recordRetained(index.retainedStatics(unit), imports, top, recorder);
      }
      if (!topDrops.isEmpty()) {
        drops.put(CodeElementId.ofType(ElementIds.idName(top)), topDrops);
      }
    }
    final long dottedConflicts =
        home.scope() instanceof UnitScope scope ? scope.dottedConflicts() : 0;
    return new UnitDependencies(
        relativePath, collector.collect(), List.of(), drops, collector.counts(), dottedConflicts);
  }

  private static List<CtType<?>> elementTypes(CtCompilationUnit unit) {
    final List<CtType<?>> tops = new ArrayList<>();
    for (final CtType<?> top : unit.getDeclaredTypes()) {
      if (ElementIds.isElementType(top)) {
        tops.add(top);
      }
    }
    return tops;
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
      Map<CodeElementId, Map<DropReason, Integer>> drops,
      Map<CodeElementId, ResolutionCounts> counts,
      long dottedConflicts) {

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
      counts = Map.copyOf(counts);
    }
  }
}
