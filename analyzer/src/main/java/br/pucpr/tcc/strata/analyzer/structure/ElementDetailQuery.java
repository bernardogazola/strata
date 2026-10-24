package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CodeMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDetailDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDetailRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementMethodRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class ElementDetailQuery {

  private static final int CC_MEAN_SCALE = 1;

  private static final String MODIFIER_SEPARATOR = " ";

  private ElementDetailQuery() {}

  public static void requireElement(CodeElementId elementId) {
    final ElementKind kind = Objects.requireNonNull(elementId, "elementId").kind();
    if (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.ELEMENT_KIND);
    }
  }

  public static ElementDetail describe(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    requireElement(elementId);
    final ElementDetailRow row =
        ElementDetailDao.element(connection, analysisPk, elementId)
            .filter(found -> !found.isExternal())
            .orElseThrow(ElementNotFoundException::new);
    final CodeMetricRow codeMetric =
        ElementDetailDao.codeMetric(connection, analysisPk, row.pk())
            .orElseThrow(
                () -> new IllegalStateException("um elemento interno sem o seu code_metric"));
    final GraphMetricRow graphMetric =
        ElementDetailDao.graphMetric(connection, analysisPk, row.pk())
            .orElseThrow(
                () -> new IllegalStateException("um elemento interno sem o seu graph_metric"));
    final boolean type = row.kind() == ElementKind.TYPE;
    final List<MethodSummary> methods = type ? methods(connection, analysisPk, row) : List.of();
    final int methodTotal =
        type ? ElementDetailDao.methodCount(connection, analysisPk, row.pk()) : 0;
    return new ElementDetail(
        row.elementId(),
        row.kind(),
        row.typeKind(),
        label(connection, analysisPk, row),
        row.qualifiedName(),
        row.packageId(),
        row.filePath(),
        lines(row.startLine(), row.endLine()),
        modifiers(row.modifiers()),
        row.isTest(),
        metrics(codeMetric),
        graphFacts(graphMetric),
        methods,
        methodTotal);
  }

  public static double ccMean(long ccSum, long unitCount) {
    if (ccSum < 0 || unitCount < 0) {
      throw new IllegalArgumentException("uma contagem de CC negativa: " + ccSum + "/" + unitCount);
    }
    if (unitCount == 0) {
      return 0d;
    }
    return BigDecimal.valueOf(ccSum)
        .divide(BigDecimal.valueOf(unitCount), CC_MEAN_SCALE, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private static String label(Connection connection, long analysisPk, ElementDetailRow row)
      throws SQLException {
    return row.kind() == ElementKind.TYPE
        ? NodeLabels.typeLabel(row.qualifiedName(), row.packageQualifiedName())
        : NodeLabels.packageLabel(
            row.qualifiedName(), CommonProperPrefix.read(connection, analysisPk));
  }

  private static List<MethodSummary> methods(
      Connection connection, long analysisPk, ElementDetailRow type) throws SQLException {
    final List<ElementMethodRow> rows =
        ElementDetailDao.methods(
            connection, analysisPk, type.pk(), AnalysisConstants.ELEMENT_METHODS_MAX);
    return rows.stream()
        .map(
            method ->
                new MethodSummary(
                    method.elementId(),
                    MethodSignatures.display(method.elementId(), type.simpleName()),
                    method.ccSum(),
                    lines(method.startLine(), method.endLine())))
        .toList();
  }

  private static ElementMetrics metrics(CodeMetricRow row) {
    if (row.unitCount() == null || row.ccSum() == null || row.ccMax() == null) {
      throw new IllegalStateException("um elemento interno com agregado de CC indefinido");
    }
    return new ElementMetrics(
        row.loc(),
        row.unitCount(),
        row.ccSum(),
        row.ccMax(),
        ccMean(row.ccSum(), row.unitCount()),
        row.ccP90());
  }

  private static GraphFacts graphFacts(GraphMetricRow row) {
    final Cycle cycle = row.sccId() == null ? null : new Cycle(row.sccId(), row.sccSize());
    return new GraphFacts(
        row.fanIn(),
        row.fanOut(),
        row.pagerank(),
        cycle,
        new ResolutionMix(
            row.resolvedOccurrences(),
            row.inferredOccurrences(),
            row.unresolvedExternalOccurrences()));
  }

  private static SourceLines lines(Integer startLine, Integer endLine) {
    return startLine == null ? null : new SourceLines(startLine, endLine);
  }

  private static List<String> modifiers(String column) {
    return column.isEmpty() ? List.of() : List.of(column.split(MODIFIER_SEPARATOR));
  }
}
