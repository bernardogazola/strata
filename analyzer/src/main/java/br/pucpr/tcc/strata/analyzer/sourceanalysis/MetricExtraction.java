package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementExtractor.DeclaredType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitExtraction.ExecutableSite;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitExtraction.TypeSite;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.CyclomaticComplexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounter;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Complexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

final class MetricExtraction {

  private long ccNanos;
  private long locNanos;

  UnitMetrics extract(ModeledBatch batch, String relativePath, UnitExtraction.Walked walked) {
    final long started = System.nanoTime();
    final Units units;
    try {
      units = units(walked);
    } finally {
      ccNanos += System.nanoTime() - started;
    }
    final long counted = System.nanoTime();
    try {
      return new UnitMetrics(relativePath, metrics(batch, relativePath, walked, units));
    } finally {
      locNanos += System.nanoTime() - counted;
    }
  }

  long ccNanos() {
    return ccNanos;
  }

  long locNanos() {
    return locNanos;
  }

  private static Units units(UnitExtraction.Walked walked) {
    final Map<CodeElementId, List<Integer>> byType = new HashMap<>();
    final Map<CodeElementId, List<Integer>> byMethod = new HashMap<>();
    for (final ExecutableSite site : walked.executables()) {
      final CtExecutable<?> executable = site.executable();
      if (!isUnit(executable)) {
        continue;
      }
      final int cc = 1 + CyclomaticComplexity.decisionPoints(executable.getBody());
      byType.computeIfAbsent(site.owner(), id -> new ArrayList<>()).add(cc);
      if (site.method() != null) {
        byMethod.computeIfAbsent(site.method(), id -> new ArrayList<>()).add(cc);
      }
    }
    for (final TypeSite site : walked.types()) {
      final int decisions = initializerDecisions(site.type());
      if (decisions > 0) {
        byType.computeIfAbsent(site.owner(), id -> new ArrayList<>()).add(1 + decisions);
      }
    }
    final Map<CodeElementId, Complexity> types = new HashMap<>();
    byType.forEach((id, values) -> types.put(id, Complexity.of(values)));
    final Map<CodeElementId, Complexity> methods = new HashMap<>();
    byMethod.forEach((id, values) -> methods.put(id, Complexity.of(values)));
    return new Units(types, methods);
  }

  private static boolean isUnit(CtExecutable<?> executable) {
    return !executable.isImplicit()
        && !(executable instanceof CtLambda<?>)
        && executable.getBody() != null;
  }

  private static int initializerDecisions(CtType<?> type) {
    int decisions = 0;
    for (final CtField<?> field : type.getFields()) {
      final CtExpression<?> initializer = field.getDefaultExpression();
      if (initializer != null) {
        decisions += CyclomaticComplexity.decisionPoints(initializer);
      }
    }
    return decisions;
  }

  private static List<CodeMetric> metrics(
      ModeledBatch batch, String relativePath, UnitExtraction.Walked walked, Units units) {
    final List<DeclaredType> types = walked.elements().types();
    if (types.isEmpty()) {
      return List.of();
    }
    final LocCounter counter = LocCounter.of(batch.text(relativePath));
    final List<CodeMetric> metrics = new ArrayList<>();
    final Set<CodeElementId> emitted = new HashSet<>();
    for (final DeclaredType type : types) {
      collect(type, counter, units, metrics, emitted);
    }
    return metrics;
  }

  private static void collect(
      DeclaredType declared,
      LocCounter counter,
      Units units,
      List<CodeMetric> metrics,
      Set<CodeElementId> emitted) {
    final CodeElementId typeId = declared.type().id();
    if (emitted.add(typeId)) {
      metrics.add(
          new CodeMetric(
              typeId,
              loc(counter, declared.type().lines()),
              units.types().getOrDefault(typeId, Complexity.NONE)));
    }
    for (final MethodElement method : declared.methods()) {
      if (emitted.add(method.id())) {
        metrics.add(
            new CodeMetric(
                method.id(), loc(counter, method.lines()), units.methods().get(method.id())));
      }
    }
    for (final DeclaredType nested : declared.nested()) {
      collect(nested, counter, units, metrics, emitted);
    }
  }

  private static int loc(LocCounter counter, SourceLines lines) {
    return lines.available() ? counter.loc(lines.start(), lines.end()) : 0;
  }

  record UnitMetrics(String relativePath, List<CodeMetric> metrics) {

    UnitMetrics {
      Objects.requireNonNull(relativePath, "relativePath");
      metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
    }
  }

  private record Units(
      Map<CodeElementId, Complexity> types, Map<CodeElementId, Complexity> methods) {}
}
