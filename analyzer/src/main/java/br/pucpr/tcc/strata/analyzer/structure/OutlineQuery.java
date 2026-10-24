package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.OutlineDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.OutlineRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureElementRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

public final class OutlineQuery {

  private OutlineQuery() {}

  public static int pageSize(Integer requested) {
    if (requested == null) {
      return AnalysisConstants.OUTLINE_PAGE_DEFAULT;
    }
    if (requested < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return Math.min(requested, AnalysisConstants.OUTLINE_PAGE_MAX);
  }

  public static void requireParent(CodeElementId parent) {
    if (parent != null
        && parent.kind() != ElementKind.PACKAGE
        && parent.kind() != ElementKind.TYPE) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.OUTLINE_PARENT_KIND);
    }
  }

  public static OutlinePage page(
      Connection connection, long analysisPk, CodeElementId parent, OutlineCursor after, int limit)
      throws SQLException {
    requireParent(parent);
    if (limit < 1) {
      throw new IllegalArgumentException("uma página tem pelo menos uma linha: " + limit);
    }
    if (parent == null) {
      return rootPage(connection, analysisPk, after, limit);
    }
    final StructureElementRow parentRow =
        StructureDao.findElement(connection, analysisPk, parent)
            .filter(row -> !row.isExternal())
            .orElseThrow(ElementNotFoundException::new);
    if (parentRow.kind() == ElementKind.TYPE) {
      if (after != null) {
        throw new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_UNKNOWN);
      }
      return OutlinePage.EMPTY;
    }
    requireListed(connection, analysisPk, after, parentRow.pk(), ElementKind.TYPE);
    final List<OutlineRow> rows =
        OutlineDao.typePage(connection, analysisPk, parentRow.pk(), keyOf(after), limit + 1);
    return page(
        rows,
        limit,
        row ->
            new OutlineItem(
                row.elementId(),
                ElementKind.TYPE,
                NodeLabels.typeLabel(row.qualifiedName(), parentRow.qualifiedName()),
                row.childCount()));
  }

  private static OutlinePage rootPage(
      Connection connection, long analysisPk, OutlineCursor after, int limit) throws SQLException {
    requireListed(connection, analysisPk, after, null, ElementKind.PACKAGE);
    final List<OutlineRow> rows =
        OutlineDao.rootPage(connection, analysisPk, keyOf(after), limit + 1);
    final String commonProperPrefix = CommonProperPrefix.read(connection, analysisPk);
    return page(
        rows,
        limit,
        row ->
            new OutlineItem(
                row.elementId(),
                ElementKind.PACKAGE,
                NodeLabels.packageLabel(row.qualifiedName(), commonProperPrefix),
                row.childCount()));
  }

  private static void requireListed(
      Connection connection, long analysisPk, OutlineCursor after, Long parentPk, ElementKind kind)
      throws SQLException {
    if (after != null
        && !OutlineDao.isListed(connection, analysisPk, after.elementPk(), parentPk, kind)) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_UNKNOWN);
    }
  }

  private static Long keyOf(OutlineCursor after) {
    return after == null ? null : after.elementPk();
  }

  private static OutlinePage page(
      List<OutlineRow> rows, int limit, Function<OutlineRow, OutlineItem> item) {
    final boolean more = rows.size() > limit;
    final List<OutlineRow> shown = more ? rows.subList(0, limit) : rows;
    final List<OutlineItem> items = shown.stream().map(item).toList();
    return new OutlinePage(items, more ? new OutlineCursor(shown.getLast().pk()) : null);
  }
}
