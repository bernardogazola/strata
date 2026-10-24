package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class ElementDetailQuery {

  private ElementDetailQuery() {}

  public static void requireElement(CodeElementId elementId) {
    final ElementKind kind = Objects.requireNonNull(elementId, "elementId").kind();
    if (kind != ElementKind.PACKAGE && kind != ElementKind.TYPE) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.ELEMENT_KIND);
    }
  }

  public static ElementDetail describe(
      Connection connection, long analysisPk, CodeElementId elementId) throws SQLException {
    throw new UnsupportedOperationException("ainda não implementado: element.describe");
  }

  public static double ccMean(long ccSum, long unitCount) {
    throw new UnsupportedOperationException("ainda não implementado: ccMean");
  }
}
