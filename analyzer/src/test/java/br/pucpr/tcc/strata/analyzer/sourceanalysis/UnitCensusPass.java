package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

  public UnitCensusPass(List<SourceRoot> roots, Occurrences occurrences) {
    this.elements = new CodeElementExtractor(roots, NO_SINK);
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
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
          dependencies.extract(path, unit);
          final long walked = System.nanoTime();
          timed(path, extracted - started, walked - extracted);
          scan(path, unit);
          scanNanos += System.nanoTime() - walked;
          return null;
        });
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
        staticFieldWrites);
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
      long staticFieldWrites) {}
}
