package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.ReadableAnalysis;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import java.sql.Connection;
import java.sql.SQLException;

final class StructureReads {

  private StructureReads() {}

  static <T> T read(
      AnalysisRegistry analyses, String method, AnalysisId id, StructureWork<T> work) {
    throw new UnsupportedOperationException("ainda não implementado: o caminho de leitura");
  }

  static ReadableAnalysis ready(AnalysisRegistry analyses, AnalysisId id) {
    throw new UnsupportedOperationException("ainda não implementado: a prontidão");
  }

  static CodeElementId elementId(String method, String wire) {
    throw new UnsupportedOperationException("ainda não implementado: o teto de id");
  }

  static RequestRejected invalid(String method, InvalidStructureRequest refused) {
    throw new UnsupportedOperationException("ainda não implementado: a tradução");
  }

  @FunctionalInterface
  interface StructureWork<T> {

    T run(Connection connection, long analysisPk) throws SQLException;
  }
}
