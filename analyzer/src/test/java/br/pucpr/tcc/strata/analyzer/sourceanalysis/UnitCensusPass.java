package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyExtractor.UnitDependencies;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

public final class UnitCensusPass implements BatchVisitor {

  private static final int SLOWEST = 5;
  private static final long NANOS_PER_MICRO = 1_000;

  private static final String CLASS_LITERAL_FIELD = "class";

  private static final ElementSink NO_SINK =
      new ElementSink() {
        @Override
        public void begin(List<SourceFile> files) {}

        @Override
        public void accept(BatchElements elements) {}

        @Override
        public void end(List<SourceFile> files, List<FileElement> fileElements) {}
      };

  public enum Construct {
    METHOD_REFERENCE,
    STATIC_FIELD,
    CLASS_LITERAL
  }

  @FunctionalInterface
  public interface Occurrences {

    void accept(
        Construct construct, String relativePath, CodeElementId source, CodeElementId target);
  }

  private final CodeElementExtractor elements;
  private final Occurrences occurrences;
  private final Function<CodeElementId, String> writtenTypeFile;
  private final Map<String, long[]> writerDropsByTopLevel = new HashMap<>();
  private final Map<DependencyType, long[]> writerDropsByType = new EnumMap<>(DependencyType.class);
  private List<SourceFile> finalFiles = List.of();
  private long survivingRows;
  private final Map<Construct, long[]> counts = new EnumMap<>(Construct.class);
  private final Map<DropReason, Integer> scanDrops = new EnumMap<>(DropReason.class);
  private final List<SlowUnit> slowest = new ArrayList<>();
  private Map<String, SourceFile> filesByPath = Map.of();
  private int units;
  private long elementNanos;
  private long dependencyNanos;
  private long scanNanos;
  private long scanCrashes;
  private long staticFieldWrites;

  public UnitCensusPass(
      List<SourceRoot> roots,
      Occurrences occurrences,
      Function<CodeElementId, String> writtenTypeFile) {
    this.elements = new CodeElementExtractor(roots, NO_SINK);
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.writtenTypeFile = Objects.requireNonNull(writtenTypeFile, "writtenTypeFile");
    for (final Construct construct : Construct.values()) {
      counts.put(construct, new long[Count.values().length]);
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
        new DependencyExtractor(elements.index(), batchTopLevelTypes(batch));
    batch.perCompilationUnit(
        (path, unit) -> {
          final long started = System.nanoTime();
          elements.extract(batch, path, unit);
          final long extracted = System.nanoTime();
          final UnitDependencies rows = dependencies.extract(path, unit);
          final long walked = System.nanoTime();
          timed(path, extracted - started, walked - extracted);
          scan(path, unit);
          scanNanos += System.nanoTime() - walked;
          replayWriterDrops(path, rows);
          return null;
        });
  }

  @Override
  public void end(SourceAnalysis analysis) {
    finalFiles = analysis.files();
  }

  public Result result() {
    final Map<Construct, ConstructCount> constructs = new EnumMap<>(Construct.class);
    counts.forEach(
        (construct, count) ->
            constructs.put(
                construct,
                new ConstructCount(
                    count[Count.SITES.ordinal()],
                    count[Count.WITHOUT_TARGET.ordinal()],
                    count[Count.DROPPED_TARGET.ordinal()],
                    count[Count.DELIVERED.ordinal()])));
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
    drops.putAll(scanDrops);
    return new Result(
        units,
        elementNanos,
        dependencyNanos,
        scanNanos,
        sortedSlowest(),
        constructs,
        drops,
        scanCrashes,
        staticFieldWrites,
        writerDrops());
  }

  private void timed(String path, long elementSpent, long dependencySpent) {
    units++;
    elementNanos += elementSpent;
    dependencyNanos += dependencySpent;
    slowest.add(new SlowUnit(path, elementSpent, dependencySpent));
    if (slowest.size() > SLOWEST * 2) {
      final List<SlowUnit> kept = sortedSlowest();
      slowest.clear();
      slowest.addAll(kept);
    }
  }

  private List<SlowUnit> sortedSlowest() {
    return slowest.stream()
        .sorted(Comparator.comparingLong(SlowUnit::totalNanos).reversed())
        .limit(SLOWEST)
        .toList();
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

  private void replayWriterDrops(String path, UnitDependencies extracted) {
    for (final ExtractedDependency row : extracted.dependencies()) {
      final Dependency dependency = row.dependency();
      if (!path.equals(writtenTypeFile.apply(dependency.source()))) {
        continue;
      }
      survivingRows++;
      if (!row.targetDeclared() || writtenTypeFile.apply(dependency.target()) != null) {
        continue;
      }
      final String topLevel =
          elements
              .index()
              .declaredTopLevel(dependency.target().sortKey())
              .orElseThrow(
                  () -> new IllegalStateException("declarado mas não indexado: " + dependency));
      final long[] byTop = writerDropsByTopLevel.computeIfAbsent(topLevel, _ -> new long[3]);
      final long[] byType = writerDropsByType.computeIfAbsent(dependency.type(), _ -> new long[2]);
      byTop[0]++;
      byTop[1] += dependency.occurrences();
      if (dependency.type() == DependencyType.EXTENDS) {
        byTop[2]++;
      }
      byType[0]++;
      byType[1] += dependency.occurrences();
    }
  }

  private WriterDrops writerDrops() {
    final Map<String, SourceFile> failedDeclarer = new HashMap<>();
    for (final SourceFile file : finalFiles) {
      if (file.status() == SourceFileStatus.PARSE_FAILED) {
        for (final String type : SimpleNameIndex.topLevelTypes(List.of(file))) {
          failedDeclarer.putIfAbsent(type, file);
        }
      }
    }
    final long[] parseFailed = new long[2];
    final long[] nested = new long[2];
    final long[] rest = new long[2];
    final Map<SourceFile, long[]> byFile = new LinkedHashMap<>();
    writerDropsByTopLevel.forEach(
        (topLevel, dropped) -> {
          final SourceFile declarer = failedDeclarer.get(topLevel);
          final long[] share;
          if (writtenTypeFile.apply(CodeElementId.ofType(topLevel)) != null) {
            share = nested;
          } else if (declarer != null) {
            share = parseFailed;
            final long[] file = byFile.computeIfAbsent(declarer, _ -> new long[4]);
            file[0] += dropped[0];
            file[1] += dropped[1];
            file[2] += dropped[2];
            file[3]++;
          } else {
            share = rest;
          }
          share[0] += dropped[0];
          share[1] += dropped[1];
        });
    final List<FileDrops> files =
        byFile.entrySet().stream()
            .map(
                entry ->
                    new FileDrops(
                        entry.getKey().relativePath(),
                        entry.getKey().cause(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[2],
                        (int) entry.getValue()[3]))
            .sorted(
                Comparator.comparingLong(FileDrops::rows)
                    .reversed()
                    .thenComparing(FileDrops::path, CodeElementId::compareCodePoints))
            .toList();
    final Map<DependencyType, long[]> byType = new EnumMap<>(DependencyType.class);
    writerDropsByType.forEach((type, counts) -> byType.put(type, counts.clone()));
    return new WriterDrops(
        survivingRows,
        new DropShare(parseFailed[0], parseFailed[1]),
        new DropShare(nested[0], nested[1]),
        new DropShare(rest[0], rest[1]),
        byType,
        files);
  }

  private void scan(String path, CtCompilationUnit unit) {
    if (unit.getUnitType() == CtCompilationUnit.UNIT_TYPE.MODULE_DECLARATION) {
      return;
    }
    try {
      final Set<String> onDemand = ElementIds.onDemandPackages(unit);
      final Map<String, String> singleTypeImports = ElementIds.singleTypeImports(unit);
      final SimpleNameIndex index = elements.index();
      for (final CtType<?> top : unit.getDeclaredTypes()) {
        if (!ElementIds.isElementType(top)) {
          continue;
        }
        final String ownPackage = ElementIds.packageName(top);
        final SimpleNameIndex.Scope scope = index.scope(ownPackage, onDemand, singleTypeImports);
        final TargetNames names =
            new TargetNames(index, Set.of(), ownPackage, singleTypeImports, scope, scanDrops);
        final SiteAttribution attribution = new SiteAttribution(scope);
        for (final CtElement element : top.getElements(new TypeFilter<>(CtElement.class))) {
          site(path, element, names, attribution);
        }
      }
    } catch (RuntimeException | StackOverflowError crash) {
      scanCrashes++;
    }
  }

  private void site(String path, CtElement element, TargetNames names, SiteAttribution where) {
    switch (element) {
      case CtExecutableReferenceExpression<?, ?> reference -> {
        final CtExecutableReference<?> executable = reference.getExecutable();
        deliver(
            Construct.METHOD_REFERENCE,
            path,
            reference,
            executable == null ? null : executable.getDeclaringType(),
            names,
            where);
      }
      case CtFieldRead<?> read -> fieldAccess(path, read, names, where);
      case CtFieldWrite<?> write -> {
        if (fieldAccess(path, write, names, where) == Construct.STATIC_FIELD) {
          staticFieldWrites++;
        }
      }
      default -> {}
    }
  }

  private Construct fieldAccess(
      String path, CtFieldAccess<?> access, TargetNames names, SiteAttribution where) {
    final CtFieldReference<?> variable = access.getVariable();
    if (access.isImplicit() || variable == null) {
      return null;
    }
    final CtExpression<?> target = access.getTarget();
    if (CLASS_LITERAL_FIELD.equals(variable.getSimpleName())
        && target instanceof CtTypeAccess<?> literal) {
      deliver(Construct.CLASS_LITERAL, path, access, literal.getAccessedType(), names, where);
      return null;
    }
    final boolean explicitType = target instanceof CtTypeAccess<?> type && !type.isImplicit();
    if (!variable.isStatic() && !explicitType) {
      return null;
    }
    return deliver(Construct.STATIC_FIELD, path, access, variable.getDeclaringType(), names, where)
        ? Construct.STATIC_FIELD
        : null;
  }

  private boolean deliver(
      Construct construct,
      String path,
      CtElement site,
      CtTypeReference<?> reference,
      TargetNames names,
      SiteAttribution where) {
    final long[] count = counts.get(construct);
    final CodeElementId source = where.source(site);
    if (source == null) {
      return false;
    }
    count[Count.SITES.ordinal()]++;
    CtTypeReference<?> named = reference;
    while (named instanceof CtArrayTypeReference<?> array) {
      named = array.getComponentType();
    }
    if (named == null
        || named instanceof CtTypeParameterReference
        || named instanceof CtIntersectionTypeReference<?>
        || named.isPrimitive()) {
      count[Count.WITHOUT_TARGET.ordinal()]++;
      return true;
    }
    final TargetNames.Target target = names.reference(named);
    if (target == null) {
      count[Count.DROPPED_TARGET.ordinal()]++;
      return true;
    }
    count[Count.DELIVERED.ordinal()]++;
    occurrences.accept(construct, path, source, target.id());
    return true;
  }

  private enum Count {
    SITES,
    WITHOUT_TARGET,
    DROPPED_TARGET,
    DELIVERED
  }

  public record SlowUnit(String path, long elementNanos, long dependencyNanos) {

    public long totalNanos() {
      return elementNanos + dependencyNanos;
    }

    public long totalMicros() {
      return totalNanos() / NANOS_PER_MICRO;
    }
  }

  public record ConstructCount(
      long sites, long withoutTarget, long droppedTarget, long delivered) {}

  public record Result(
      int units,
      long elementNanos,
      long dependencyNanos,
      long scanNanos,
      List<SlowUnit> slowest,
      Map<Construct, ConstructCount> constructs,
      Map<DropReason, Integer> scanDrops,
      long scanCrashes,
      long staticFieldWrites,
      WriterDrops writerDrops) {}

  public record WriterDrops(
      long survivingRows,
      DropShare intoParseFailedFile,
      DropShare nestedOfWrittenType,
      DropShare rest,
      Map<DependencyType, long[]> byType,
      List<FileDrops> parseFailedFiles) {

    public long rows() {
      return intoParseFailedFile.rows() + nestedOfWrittenType.rows() + rest.rows();
    }

    public long occurrences() {
      return intoParseFailedFile.occurrences()
          + nestedOfWrittenType.occurrences()
          + rest.occurrences();
    }
  }

  public record DropShare(long rows, long occurrences) {}

  public record FileDrops(
      String path,
      ParseFailureCause cause,
      long rows,
      long occurrences,
      long extendsRows,
      int topLevelTypes) {}
}
