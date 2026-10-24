package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyEndRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyPageDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyTotalsRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageDependencyRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.TypeDependencyRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DependencyPageQuery {

  private static final Comparator<Ranked> BY_RANK =
      Comparator.comparingLong((Ranked ranked) -> ranked.entry().weight())
          .reversed()
          .thenComparing(ranked -> ranked.entry().elementId());

  private DependencyPageQuery() {}

  public static int pageSize(Integer requested) {
    if (requested == null) {
      return AnalysisConstants.DEPENDENCY_PAGE_DEFAULT;
    }
    if (requested < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return Math.min(requested, AnalysisConstants.DEPENDENCY_PAGE_MAX);
  }

  public static void requireElement(CodeElementId elementId) {
    ElementDetailQuery.requireElement(elementId);
  }

  public static DependencyPage page(
      Connection connection,
      long analysisPk,
      CodeElementId elementId,
      DependencyDirection direction,
      DependencyCursor after,
      int limit)
      throws SQLException {
    requireElement(elementId);
    Objects.requireNonNull(direction, "direction");
    if (limit < 1) {
      throw new IllegalArgumentException("uma página tem pelo menos uma linha: " + limit);
    }
    final StructureElementRow anchor =
        StructureDao.findElement(connection, analysisPk, elementId)
            .filter(row -> !row.isExternal())
            .orElseThrow(ElementNotFoundException::new);
    final boolean type = anchor.kind() == ElementKind.TYPE;
    final List<Ranked> ranked =
        type
            ? typeRanking(connection, analysisPk, anchor, direction)
            : packageRanking(connection, analysisPk, anchor, direction);
    final DependencyTotalsRow totals =
        type
            ? DependencyPageDao.typeTotals(connection, analysisPk, anchor.pk())
            : DependencyPageDao.packageTotals(connection, analysisPk, anchor.pk());
    final int from = after == null ? 0 : resumeAfter(ranked, after, anchor, direction);
    final int to = Math.min(ranked.size(), from + limit);
    final List<DependencyEntry> items =
        ranked.subList(from, to).stream().map(Ranked::entry).toList();
    final DependencyCursor next =
        to < ranked.size()
            ? new DependencyCursor(direction, anchor.pk(), ranked.get(to - 1).otherPk())
            : null;
    return new DependencyPage(
        items, next, new DependencyTotals(totals.in(), totals.out(), totals.outExternal()));
  }

  private static int resumeAfter(
      List<Ranked> ranked,
      DependencyCursor after,
      StructureElementRow anchor,
      DependencyDirection direction) {
    if (after.direction() != direction || after.anchorPk() != anchor.pk()) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_UNKNOWN);
    }
    for (int index = 0; index < ranked.size(); index++) {
      if (ranked.get(index).otherPk() == after.lastPk()) {
        return index + 1;
      }
    }
    throw new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_UNKNOWN);
  }

  private static List<Ranked> typeRanking(
      Connection connection,
      long analysisPk,
      StructureElementRow anchor,
      DependencyDirection direction)
      throws SQLException {
    final boolean inbound = direction == DependencyDirection.IN;
    final List<TypeDependencyRow> rows =
        inbound
            ? DependencyPageDao.inbound(connection, analysisPk, anchor.pk())
            : DependencyPageDao.outbound(connection, analysisPk, anchor.pk());
    final Map<CodeElementId, DependencyEndRow> others = HashMap.newHashMap(rows.size());
    final List<PairRow> pairRows = new ArrayList<>(rows.size());
    for (final TypeDependencyRow row : rows) {
      others.put(row.other().elementId(), row.other());
      pairRows.add(new PairRow(row.dependency(), row.ambiguous()));
    }
    final List<Ranked> internal = new ArrayList<>();
    final List<Ranked> external = new ArrayList<>();
    for (final PairEdge pair : PairEdges.aggregate(pairRows)) {
      final DependencyEndRow other = others.get(inbound ? pair.source() : pair.target());
      (other.isExternal() ? external : internal).add(new Ranked(entry(pair, other), other.pk()));
    }
    return blocks(internal, external);
  }

  private static List<Ranked> packageRanking(
      Connection connection,
      long analysisPk,
      StructureElementRow anchor,
      DependencyDirection direction)
      throws SQLException {
    final boolean inbound = direction == DependencyDirection.IN;
    final List<PackageDependencyRow> rows =
        inbound
            ? DependencyPageDao.packageInbound(connection, analysisPk, anchor.pk())
            : DependencyPageDao.packageOutbound(connection, analysisPk, anchor.pk());
    if (rows.isEmpty()) {
      return List.of();
    }
    final String commonProperPrefix = CommonProperPrefix.read(connection, analysisPk);
    final List<Ranked> internal = new ArrayList<>(rows.size());
    for (final PackageDependencyRow row : rows) {
      final DependencyEndRow other = row.other();
      final PairEdge pair =
          inbound
              ? PairEdges.packageEdge(other.elementId(), anchor.elementId(), row.edge())
              : PairEdges.packageEdge(anchor.elementId(), other.elementId(), row.edge());
      final String label = NodeLabels.packageLabel(other.qualifiedName(), commonProperPrefix);
      internal.add(new Ranked(entry(pair, other, label), other.pk()));
    }
    return blocks(internal, List.of());
  }

  private static List<Ranked> blocks(List<Ranked> internal, List<Ranked> external) {
    final List<Ranked> ranked = new ArrayList<>(internal.size() + external.size());
    ranked.addAll(internal.stream().sorted(BY_RANK).toList());
    ranked.addAll(external.stream().sorted(BY_RANK).toList());
    return List.copyOf(ranked);
  }

  private static DependencyEntry entry(PairEdge pair, DependencyEndRow other) {
    final String label =
        other.isExternal()
            ? NodeLabels.clamp(other.qualifiedName())
            : NodeLabels.typeLabel(other.qualifiedName(), other.packageQualifiedName());
    return entry(pair, other, label);
  }

  private static DependencyEntry entry(PairEdge pair, DependencyEndRow other, String label) {
    return new DependencyEntry(
        pair.id(),
        other.elementId(),
        other.elementId().kind(),
        label,
        other.qualifiedName(),
        other.packageId(),
        other.isExternal(),
        pair.ambiguous(),
        pair.resolution(),
        pair.types(),
        pair.weight());
  }

  private record Ranked(DependencyEntry entry, long otherPk) {}
}
