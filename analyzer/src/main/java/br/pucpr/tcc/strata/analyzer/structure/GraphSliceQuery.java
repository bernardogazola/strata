package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SliceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SlicePackageRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SliceTypeRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureElementRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class GraphSliceQuery {

  private GraphSliceQuery() {}

  public static void requireParent(GraphLevel level, CodeElementId parent) {
    switch (Objects.requireNonNull(level, "level")) {
      case PACKAGE -> {
        if (parent != null) {
          throw new InvalidStructureRequest(InvalidStructureRequest.Reason.PARENT_NOT_ALLOWED);
        }
      }
      case TYPE -> {
        if (parent == null || parent.kind() != ElementKind.PACKAGE) {
          throw new InvalidStructureRequest(InvalidStructureRequest.Reason.PARENT_NOT_A_PACKAGE);
        }
      }
      case TYPE_NEIGHBORHOOD ->
          throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LEVEL_NOT_SERVED);
    }
  }

  public static Slice slice(
      Connection connection,
      long analysisPk,
      GraphLevel level,
      CodeElementId parent,
      SliceBudget budget)
      throws SQLException {
    requireParent(level, parent);
    Objects.requireNonNull(budget, "budget");
    return level == GraphLevel.PACKAGE
        ? packageSlice(connection, analysisPk, budget)
        : typeSlice(connection, analysisPk, parent, budget);
  }

  private static Slice packageSlice(Connection connection, long analysisPk, SliceBudget budget)
      throws SQLException {
    final List<SlicePackageRow> packages = SliceDao.packageNodes(connection, analysisPk);
    final List<PackageEdgeRow> rows = PackageEdgeDao.list(connection, analysisPk);
    final String commonProperPrefix = CommonProperPrefix.read(connection, analysisPk);
    final Function<SlicePackageRow, SliceNode> packageNode =
        row -> packageNode(row, commonProperPrefix);
    final boolean aggregated = packages.size() > budget.nodes();

    final List<SliceNode> nodes;
    final List<SliceEdge> edges;
    if (aggregated) {
      final Set<String> names = HashSet.newHashSet(packages.size());
      packages.forEach(row -> names.add(row.qualifiedName()));
      final Map<String, PackageGroup> groupOf = PrefixRollup.groups(names, budget.nodes());
      nodes = PackageAggregation.nodes(packages, rows, groupOf, packageNode, commonProperPrefix);
      edges = PackageAggregation.edges(packages, rows, groupOf);
    } else {
      nodes = packages.stream().map(packageNode).toList();
      edges = packageEdges(packages, rows);
    }
    final SliceRanking.Selection kept = SliceRanking.select(nodes, edges, budget);
    return new Slice(
        GraphLevel.PACKAGE,
        null,
        kept.nodes(),
        kept.edges(),
        new SliceTotals(packages.size(), rows.size()),
        kept.truncated(),
        aggregated,
        budget);
  }

  private static Slice typeSlice(
      Connection connection, long analysisPk, CodeElementId parent, SliceBudget budget)
      throws SQLException {
    final StructureElementRow parentRow =
        StructureDao.findElement(connection, analysisPk, parent)
            .filter(row -> row.kind() == ElementKind.PACKAGE && !row.isExternal())
            .orElseThrow(ElementNotFoundException::new);
    final List<SliceTypeRow> types = SliceDao.typeNodes(connection, analysisPk, parentRow.pk());
    final List<SliceEdge> edges =
        typeEdges(SliceDao.intraPackageDependencies(connection, analysisPk, parentRow.pk()));
    final List<SliceNode> nodes =
        types.stream().map(row -> typeNode(row, parentRow.qualifiedName())).toList();
    final SliceRanking.Selection kept = SliceRanking.select(nodes, edges, budget);
    return new Slice(
        GraphLevel.TYPE,
        parent,
        kept.nodes(),
        kept.edges(),
        new SliceTotals(types.size(), edges.size()),
        kept.truncated(),
        false,
        budget);
  }

  private static SliceNode packageNode(SlicePackageRow row, String commonProperPrefix) {
    return new SliceNode(
        row.elementId().value(),
        GraphNodeKind.PACKAGE,
        NodeLabels.packageLabel(row.qualifiedName(), commonProperPrefix),
        row.loc(),
        row.typeCount(),
        row.fanIn(),
        row.fanOut(),
        row.pagerank(),
        new ResolutionMix(
            row.resolvedOccurrences(),
            row.inferredOccurrences(),
            row.unresolvedExternalOccurrences()));
  }

  private static SliceNode typeNode(SliceTypeRow row, String packageQualifiedName) {
    return new SliceNode(
        row.elementId().value(),
        GraphNodeKind.TYPE,
        NodeLabels.typeLabel(row.qualifiedName(), packageQualifiedName),
        row.loc(),
        1,
        row.fanIn(),
        row.fanOut(),
        row.pagerank(),
        new ResolutionMix(
            row.resolvedOccurrences(),
            row.inferredOccurrences(),
            row.unresolvedExternalOccurrences()));
  }

  private static List<SliceEdge> packageEdges(
      List<SlicePackageRow> packages, List<PackageEdgeRow> rows) {
    final Map<Long, CodeElementId> idByPk = HashMap.newHashMap(packages.size());
    packages.forEach(row -> idByPk.put(row.pk(), row.elementId()));
    final List<SliceEdge> edges = new ArrayList<>(rows.size());
    for (final PackageEdgeRow row : rows) {
      final CodeElementId source = endpoint(idByPk, row.sourcePk());
      final CodeElementId target = endpoint(idByPk, row.targetPk());
      edges.add(sliceEdge(PairEdges.packageEdge(source, target, row)));
    }
    return edges;
  }

  private static CodeElementId endpoint(Map<Long, CodeElementId> idByPk, long packagePk) {
    final CodeElementId id = idByPk.get(packagePk);
    if (id == null) {
      throw new IllegalStateException(
          "uma aresta de pacote para um pacote fora do conjunto de nós");
    }
    return id;
  }

  private static List<SliceEdge> typeEdges(List<Dependency> rows) {
    final List<PairRow> pairRows = rows.stream().map(PairRow::unambiguous).toList();
    return PairEdges.aggregate(pairRows).stream().map(GraphSliceQuery::sliceEdge).toList();
  }

  private static SliceEdge sliceEdge(PairEdge pair) {
    return new SliceEdge(
        pair.id(),
        pair.source().value(),
        pair.target().value(),
        pair.types(),
        pair.weight(),
        pair.resolution());
  }
}
