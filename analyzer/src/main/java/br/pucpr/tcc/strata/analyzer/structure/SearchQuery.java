package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SearchDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SearchRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class SearchQuery {

  private static final Set<ElementKind> INDEXED_KINDS =
      Collections.unmodifiableSet(EnumSet.of(ElementKind.PACKAGE, ElementKind.TYPE));

  private SearchQuery() {}

  public static Set<ElementKind> kinds(List<ElementKind> requested) {
    if (requested == null) {
      return INDEXED_KINDS;
    }
    final Set<ElementKind> applied = EnumSet.noneOf(ElementKind.class);
    for (final ElementKind kind : requested) {
      if (kind == null) {
        throw new InvalidStructureRequest(InvalidStructureRequest.Reason.KIND_REQUIRED);
      }
      if (INDEXED_KINDS.contains(kind)) {
        applied.add(kind);
      }
    }
    return Collections.unmodifiableSet(applied);
  }

  public static int resultLimit(Integer requested) {
    if (requested == null) {
      return AnalysisConstants.SEARCH_RESULTS_MAX;
    }
    if (requested < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return Math.min(requested, AnalysisConstants.SEARCH_RESULTS_MAX);
  }

  public static List<SearchHit> search(
      Connection connection, long analysisPk, SearchTerm term, Set<ElementKind> kinds, int limit)
      throws SQLException {
    if (kinds.isEmpty()) {
      return List.of();
    }
    if (!INDEXED_KINDS.containsAll(kinds)) {
      throw new IllegalArgumentException("só PACKAGE e TYPE são buscados: " + kinds);
    }
    final List<SearchRow> rows =
        SearchDao.search(connection, analysisPk, term.folded(), kinds, limit);
    final boolean anyPackage = rows.stream().anyMatch(row -> row.packageId() == null);
    final String commonProperPrefix =
        anyPackage ? CommonProperPrefix.read(connection, analysisPk) : "";
    final List<SearchHit> hits = new ArrayList<>(rows.size());
    for (final SearchRow row : rows) {
      hits.add(hit(row, commonProperPrefix));
    }
    return List.copyOf(hits);
  }

  private static SearchHit hit(SearchRow row, String commonProperPrefix) {
    if (row.elementId().kind() == ElementKind.PACKAGE) {
      return new SearchHit(
          row.elementId(),
          ElementKind.PACKAGE,
          NodeLabels.packageLabel(row.qualifiedName(), commonProperPrefix),
          row.qualifiedName(),
          null);
    }
    return new SearchHit(
        row.elementId(),
        ElementKind.TYPE,
        NodeLabels.typeLabel(row.qualifiedName(), row.packageQualifiedName()),
        row.qualifiedName(),
        row.packageId());
  }
}
