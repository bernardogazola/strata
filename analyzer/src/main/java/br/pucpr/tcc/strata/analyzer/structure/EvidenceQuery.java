package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
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
    throw new UnsupportedOperationException("ainda não implementado: a evidência");
  }
}
