package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.UnitElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyExtractor.UnitDependencies;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StructureExtractor implements BatchVisitor {

  private final StructureSink sink;
  private final CodeElementExtractor elements;
  private final Map<DropReason, Long> drops = new EnumMap<>(DropReason.class);
  private final JavaLangTypes javaLang = new JavaLangTypes();
  private ResolutionCounts counts = ResolutionCounts.NONE;
  private long dottedConflicts;
  private Map<String, SourceFile> filesByPath = Map.of();

  public StructureExtractor(List<SourceRoot> roots, StructureSink sink) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.elements =
        new CodeElementExtractor(List.copyOf(Objects.requireNonNull(roots, "roots")), sink);
    for (final DropReason reason : DropReason.values()) {
      drops.put(reason, 0L);
    }
  }

  @Override
  public void begin(List<SourceFile> files) {
    elements.begin(files);
    final Map<String, SourceFile> byPath = new HashMap<>();
    for (final SourceFile file : files) {
      byPath.put(file.relativePath(), file);
    }
    filesByPath = byPath;
  }

  @Override
  public void visit(ModeledBatch batch) {
    final DependencyExtractor dependencies =
        new DependencyExtractor(batch.index(), batchTopLevelTypes(batch), javaLang);
    final List<UnitFacts> units =
        batch.perCompilationUnit(
            (path, unit) ->
                new UnitFacts(
                    elements.extract(batch, path, unit), dependencies.extract(path, unit)));
    final BatchElements batchElements =
        elements.dedupe(units.stream().map(UnitFacts::elements).toList());
    final List<UnitDependencies> unitDependencies =
        units.stream().map(UnitFacts::dependencies).toList();
    final Map<CodeElementId, String> declaredIn = declaredIn(batchElements);
    count(unitDependencies, declaredIn);
    sink.accept(new BatchStructure(batchElements, survivors(unitDependencies, declaredIn)));
  }

  @Override
  public void end(SourceAnalysis analysis) {
    elements.end(analysis);
    final long total = drops.values().stream().mapToLong(Long::longValue).sum();
    if (total > 0) {
      Log.warn("a extração de dependências descartou " + total + " ocorrências: " + drops);
    }
    Log.info(
        "resolução de dependências por ocorrência: resolvidas "
            + counts.resolved()
            + ", inferidas "
            + counts.inferred()
            + ", não resolvidas "
            + counts.unresolved()
            + " ("
            + counts.ambiguous()
            + " ambíguas); nomes com ponto e duas leituras: "
            + dottedConflicts);
  }

  public List<SourceIssue> issues() {
    return elements.issues();
  }

  public ResolutionCounts resolutionCounts() {
    return counts;
  }

  public Map<DropReason, Long> droppedOccurrences() {
    return Collections.unmodifiableMap(new EnumMap<>(drops));
  }

  private Set<String> batchTopLevelTypes(ModeledBatch batch) {
    final List<SourceFile> files = new ArrayList<>();
    for (final String path : batch.files()) {
      final SourceFile file = filesByPath.get(path);
      if (file != null) {
        files.add(file);
      }
    }
    return Set.copyOf(SimpleNameIndex.topLevelTypes(files));
  }

  private static Map<CodeElementId, String> declaredIn(BatchElements batchElements) {
    final Map<CodeElementId, String> declaredIn = new HashMap<>();
    for (final TypeElement type : batchElements.types()) {
      declaredIn.put(type.id(), type.filePath());
    }
    return declaredIn;
  }

  private static BatchDependencies survivors(
      List<UnitDependencies> units, Map<CodeElementId, String> declaredIn) {
    final List<ExtractedDependency> rows = new ArrayList<>();
    final List<ModuleDependency> modules = new ArrayList<>();
    for (final UnitDependencies unit : units) {
      for (final ExtractedDependency row : unit.dependencies()) {
        if (unit.relativePath().equals(declaredIn.get(row.dependency().source()))) {
          rows.add(row);
        }
      }
      modules.addAll(unit.modules());
    }
    return new BatchDependencies(rows, modules);
  }

  private void count(List<UnitDependencies> units, Map<CodeElementId, String> declaredIn) {
    for (final UnitDependencies unit : units) {
      unit.drops()
          .forEach(
              (top, reasons) -> {
                if (unit.relativePath().equals(declaredIn.get(top))) {
                  reasons.forEach((reason, count) -> drops.merge(reason, (long) count, Long::sum));
                }
              });
      unit.counts()
          .forEach(
              (source, occurrences) -> {
                if (unit.relativePath().equals(declaredIn.get(source))) {
                  counts = sum(counts, occurrences);
                }
              });
      dottedConflicts += unit.dottedConflicts();
    }
  }

  private static ResolutionCounts sum(ResolutionCounts left, ResolutionCounts right) {
    return new ResolutionCounts(
        left.resolved() + right.resolved(),
        left.inferred() + right.inferred(),
        left.unresolved() + right.unresolved(),
        left.ambiguous() + right.ambiguous());
  }

  public enum DropReason {
    LOCAL_TARGET,
    INVALID_NAME,
    ID_TOO_LONG,
    DEFAULT_PACKAGE_COLLISION,
    CALL_WITHOUT_TARGET
  }

  private record UnitFacts(UnitElements elements, UnitDependencies dependencies) {}
}
