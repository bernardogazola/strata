package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EdgeEvidenceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EdgeEvidenceRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceMethodRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureElementRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class EvidenceQuery {

  private EvidenceQuery() {}

  public static int resultLimit(Integer requested) {
    if (requested == null) {
      return AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX;
    }
    if (requested < 1) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE);
    }
    return Math.min(requested, AnalysisConstants.DEPENDENCY_EVIDENCE_RESULTS_MAX);
  }

  public static void requireEnds(CodeElementId source, CodeElementId target) {
    if (Objects.requireNonNull(source, "source").kind() != ElementKind.TYPE) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.SOURCE_KIND);
    }
    if (Objects.requireNonNull(target, "target").kind() != ElementKind.TYPE) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.TARGET_KIND);
    }
    if (source.equals(target)) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.SAME_ENDS);
    }
  }

  public static List<RelationshipEvidence> evidence(
      Connection connection, long analysisPk, CodeElementId source, CodeElementId target, int limit)
      throws SQLException {
    requireEnds(source, target);
    final StructureElementRow sourceRow =
        StructureDao.findElement(connection, analysisPk, source)
            .filter(row -> !row.isExternal())
            .orElseThrow(ElementNotFoundException::new);
    final StructureElementRow targetRow =
        StructureDao.findElement(connection, analysisPk, target)
            .orElseThrow(ElementNotFoundException::new);
    final List<EdgeEvidenceRow> stored =
        EdgeEvidenceDao.evidence(connection, analysisPk, sourceRow.pk(), targetRow.pk());
    if (stored.isEmpty()
        && !EdgeEvidenceDao.pairExists(connection, analysisPk, sourceRow.pk(), targetRow.pk())) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.PAIR_WITHOUT_DEPENDENCY);
    }
    return EvidenceSelection.select(stored.stream().map(EvidenceQuery::evidence).toList(), limit);
  }

  private static RelationshipEvidence evidence(EdgeEvidenceRow row) {
    return new RelationshipEvidence(
        row.type(),
        row.filePath(),
        row.line(),
        row.resolution(),
        methodRef(row.fromMethod()),
        methodRef(row.toMethod()));
  }

  private static MethodRef methodRef(EvidenceMethodRow row) {
    if (row == null) {
      return null;
    }
    return new MethodRef(
        row.elementId(),
        MethodSignatures.display(row.elementId(), row.ownerSimpleName()),
        row.ownerId(),
        NodeLabels.typeLabel(row.ownerQualifiedName(), row.ownerPackageQualifiedName()));
  }
}
