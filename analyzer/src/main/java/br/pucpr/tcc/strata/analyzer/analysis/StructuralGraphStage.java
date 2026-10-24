package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.GraphInput;
import br.pucpr.tcc.strata.analyzer.graph.PackageEdge;
import br.pucpr.tcc.strata.analyzer.graph.PackageMetrics;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphAnalysis;
import br.pucpr.tcc.strata.analyzer.graph.StructuralGraphResult;
import br.pucpr.tcc.strata.analyzer.graph.TypeMetrics;
import br.pucpr.tcc.strata.analyzer.graph.TypeNode;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphInputDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphPackageRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphSource;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphTypeRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class StructuralGraphStage implements StageExecutor {

  private final ExternalPackages packages;
  private final UnaryOperator<BooleanSupplier> cancellationSeam;

  public StructuralGraphStage(ExternalPackages packages) {
    this(packages, UnaryOperator.identity());
  }

  StructuralGraphStage(ExternalPackages packages, UnaryOperator<BooleanSupplier> cancellationSeam) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.cancellationSeam = Objects.requireNonNull(cancellationSeam, "cancellationSeam");
  }

  @Override
  public StageOutcome execute(StageContext context) throws SQLException {
    final BooleanSupplier cancelled = cancellationSeam.apply(context.cancellation().asSupplier());
    final long analysisPk = context.analysisPk();
    final long readStart = System.nanoTime();
    final GraphSource source =
        context
            .connections()
            .read(connection -> GraphInputDao.read(connection, analysisPk, cancelled));
    context.cancellation().throwIfCancelled();
    final long typeNodes = source.types().size();
    context.progress().report(ProgressUnit.TYPES, 0, typeNodes);
    final long computeStart = System.nanoTime();
    final StructuralGraphResult result =
        StructuralGraphAnalysis.compute(input(source), packages, cancelled);
    context.cancellation().throwIfCancelled();
    final Map<CodeElementId, Long> keys = keys(source);
    final List<GraphMetricRow> metricRows = metricRows(result, keys);
    final List<PackageEdgeRow> edgeRows = edgeRows(result, keys);
    final long writeStart = System.nanoTime();
    context
        .connections()
        .write(
            connection -> {
              GraphMetricDao.insert(connection, analysisPk, metricRows, cancelled);
              PackageEdgeDao.insert(connection, analysisPk, edgeRows, cancelled);
              return null;
            });
    final long writeEnd = System.nanoTime();
    context.progress().finish(ProgressUnit.TYPES, typeNodes, typeNodes);
    final Map<String, Long> counters = result.counters().asMap();
    Log.info(
        "análise "
            + context.analysisId().value()
            + " STRUCTURAL_GRAPH "
            + counters
            + " readMillis="
            + millis(readStart, computeStart)
            + " computeMillis="
            + millis(computeStart, writeStart)
            + " writeMillis="
            + millis(writeStart, writeEnd));
    return StageOutcome.completed(StageCounters.of(counters));
  }

  private static GraphInput input(GraphSource source) {
    final List<TypeNode> types =
        source.types().stream().map(type -> new TypeNode(type.id(), type.packageId())).toList();
    return new GraphInput(types, source.rows());
  }

  private static Map<CodeElementId, Long> keys(GraphSource source) {
    final Map<CodeElementId, Long> keys = new HashMap<>();
    for (final GraphTypeRow type : source.types()) {
      keys.put(type.id(), type.pk());
    }
    for (final GraphPackageRow pkg : source.packages()) {
      keys.put(pkg.id(), pkg.pk());
    }
    return keys;
  }

  private static long key(Map<CodeElementId, Long> keys, CodeElementId id) {
    final Long pk = keys.get(id);
    if (pk == null) {
      throw new IllegalStateException("um vértice do grafo sem elemento na análise: " + id);
    }
    return pk;
  }

  private static List<GraphMetricRow> metricRows(
      StructuralGraphResult result, Map<CodeElementId, Long> keys) {
    return Stream.concat(
            result.types().stream().map(type -> typeRow(key(keys, type.id()), type)),
            result.packages().stream().map(pkg -> packageRow(key(keys, pkg.id()), pkg)))
        .sorted(Comparator.comparingLong(GraphMetricRow::elementPk))
        .toList();
  }

  private static GraphMetricRow typeRow(long pk, TypeMetrics type) {
    final Cycle cycle = type.cycle();
    final ResolutionMix mix = type.resolutionMix();
    return new GraphMetricRow(
        pk,
        ElementKind.TYPE,
        type.fanIn(),
        type.fanOut(),
        type.fanInResolved(),
        type.fanOutResolved(),
        type.fanOutExternal(),
        type.externalFanIn(),
        type.pagerank(),
        type.pagerankOut(),
        cycle == null ? null : cycle.id(),
        cycle == null ? null : cycle.size(),
        type.bridgeSpan(),
        null,
        null,
        null,
        mix.resolved(),
        mix.inferred(),
        mix.unresolvedExternal());
  }

  private static GraphMetricRow packageRow(long pk, PackageMetrics pkg) {
    final Cycle cycle = pkg.cycle();
    final ResolutionMix mix = pkg.resolutionMix();
    return new GraphMetricRow(
        pk,
        ElementKind.PACKAGE,
        pkg.fanIn(),
        pkg.fanOut(),
        null,
        null,
        null,
        null,
        pkg.pagerank(),
        pkg.pagerankOut(),
        cycle == null ? null : cycle.id(),
        cycle == null ? null : cycle.size(),
        null,
        pkg.componentId(),
        pkg.internalCohesion(),
        pkg.betweenness(),
        mix.resolved(),
        mix.inferred(),
        mix.unresolvedExternal());
  }

  private static List<PackageEdgeRow> edgeRows(
      StructuralGraphResult result, Map<CodeElementId, Long> keys) {
    return result.packageEdges().stream()
        .map(edge -> edgeRow(edge, keys))
        .sorted(
            Comparator.comparingLong(PackageEdgeRow::sourcePk)
                .thenComparingLong(PackageEdgeRow::targetPk))
        .toList();
  }

  private static PackageEdgeRow edgeRow(PackageEdge edge, Map<CodeElementId, Long> keys) {
    return new PackageEdgeRow(
        key(keys, edge.source()),
        key(keys, edge.target()),
        edge.typePairCount(),
        edge.occurrenceTotal(),
        edge.resolution(),
        edge.occurrences());
  }

  private static long millis(long fromNanos, long toNanos) {
    return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
  }
}
