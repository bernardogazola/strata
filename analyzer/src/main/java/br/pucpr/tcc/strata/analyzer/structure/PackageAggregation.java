package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SlicePackageRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class PackageAggregation {

  private PackageAggregation() {}

  static List<SliceNode> nodes(
      List<SlicePackageRow> packages,
      List<PackageEdgeRow> edges,
      Map<String, PackageGroup> groupOf,
      Function<SlicePackageRow, SliceNode> packageNode,
      String commonProperPrefix) {
    final Map<Long, PackageGroup> groupByPk = groupByPk(packages, groupOf);
    final Map<PackageGroup, Set<Long>> into = new HashMap<>();
    final Map<PackageGroup, Set<Long>> outOf = new HashMap<>();
    for (final PackageEdgeRow edge : edges) {
      final PackageGroup source = groupByPk.get(edge.sourcePk());
      final PackageGroup target = groupByPk.get(edge.targetPk());
      if (!source.equals(target)) {
        into.computeIfAbsent(target, group -> new HashSet<>()).add(edge.sourcePk());
        outOf.computeIfAbsent(source, group -> new HashSet<>()).add(edge.targetPk());
      }
    }
    final Map<PackageGroup, List<SlicePackageRow>> members = new LinkedHashMap<>();
    for (final SlicePackageRow row : packages) {
      members
          .computeIfAbsent(groupOf.get(row.qualifiedName()), group -> new ArrayList<>())
          .add(row);
    }
    final List<SliceNode> nodes = new ArrayList<>(members.size());
    members.forEach(
        (group, rows) ->
            nodes.add(
                group.isPrefix()
                    ? prefixNode(
                        group,
                        rows,
                        into.getOrDefault(group, Set.of()).size(),
                        outOf.getOrDefault(group, Set.of()).size(),
                        commonProperPrefix)
                    : packageNode.apply(rows.getFirst())));
    return nodes;
  }

  static List<SliceEdge> edges(
      List<SlicePackageRow> packages,
      List<PackageEdgeRow> edges,
      Map<String, PackageGroup> groupOf) {
    final Map<Long, PackageGroup> groupByPk = groupByPk(packages, groupOf);
    final Map<Pair, Sum> sums = new LinkedHashMap<>();
    for (final PackageEdgeRow edge : edges) {
      final PackageGroup source = groupByPk.get(edge.sourcePk());
      final PackageGroup target = groupByPk.get(edge.targetPk());
      if (!source.equals(target)) {
        sums.computeIfAbsent(new Pair(source.nodeId(), target.nodeId()), pair -> new Sum())
            .add(edge);
      }
    }
    final List<SliceEdge> grouped = new ArrayList<>(sums.size());
    sums.forEach((pair, sum) -> grouped.add(sum.edge(pair)));
    return grouped;
  }

  private static Map<Long, PackageGroup> groupByPk(
      List<SlicePackageRow> packages, Map<String, PackageGroup> groupOf) {
    final Map<Long, PackageGroup> byPk = HashMap.newHashMap(packages.size());
    for (final SlicePackageRow row : packages) {
      byPk.put(row.pk(), groupOf.get(row.qualifiedName()));
    }
    return byPk;
  }

  private static SliceNode prefixNode(
      PackageGroup group,
      List<SlicePackageRow> rows,
      int fanIn,
      int fanOut,
      String commonProperPrefix) {
    long loc = 0;
    int typeCount = 0;
    ResolutionMix mix = ResolutionMix.NONE;
    BigDecimal pagerank = BigDecimal.ZERO;
    for (final SlicePackageRow row : rows) {
      loc = Math.addExact(loc, row.loc());
      typeCount = Math.addExact(typeCount, row.typeCount());
      mix =
          mix.plus(
              new ResolutionMix(
                  row.resolvedOccurrences(),
                  row.inferredOccurrences(),
                  row.unresolvedExternalOccurrences()));
      pagerank = pagerank.add(new BigDecimal(row.pagerank()));
    }
    return new SliceNode(
        group.nodeId(),
        group.kind(),
        NodeLabels.prefixLabel(group.name(), commonProperPrefix),
        loc,
        typeCount,
        fanIn,
        fanOut,
        Math.min(1d, pagerank.doubleValue()),
        mix);
  }

  private record Pair(String source, String target) {}

  private static final class Sum {
    private final Map<DependencyType, Long> types = new EnumMap<>(DependencyType.class);
    private long weight;
    private ResolutionQuality resolution = ResolutionQuality.RESOLVED;

    void add(PackageEdgeRow edge) {
      edge.occurrences().forEach((type, count) -> types.merge(type, (long) count, Math::addExact));
      weight = Math.addExact(weight, edge.typePairCount());
      resolution = ResolutionQuality.weakest(resolution, edge.resolution());
    }

    SliceEdge edge(Pair pair) {
      return new SliceEdge(
          EdgeIds.of(pair.source(), pair.target()),
          pair.source(),
          pair.target(),
          types,
          weight,
          resolution);
    }
  }
}
