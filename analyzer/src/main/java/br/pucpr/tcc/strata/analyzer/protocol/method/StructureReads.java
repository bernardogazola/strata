package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.ReadableAnalysis;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StructureDao;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.ElementNotFoundException;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import java.sql.Connection;
import java.sql.SQLException;

final class StructureReads {

  private static final String ID_TOO_LONG =
      " passa de " + AnalysisConstants.MAX_ELEMENT_ID_BYTES + " bytes em UTF-8";

  private static final String ID_MALFORMED = " não é um id de elemento";

  private static final String FIELD_REQUIRED = " é obrigatório";

  private StructureReads() {}

  static <T> T read(
      AnalysisRegistry analyses, String method, AnalysisId id, StructureWork<T> work) {
    final ReadableAnalysis analysis = ready(analyses, id);
    try {
      return analysis
          .connections()
          .read(
              connection -> {
                if (!StructureDao.isServable(connection, analysis.analysisPk())) {
                  throw new RequestRejected(ErrorCodes.analysisNotFound());
                }
                return work.run(connection, analysis.analysisPk());
              });
    } catch (ElementNotFoundException absent) {
      throw new RequestRejected(ErrorCodes.elementNotFound());
    } catch (InvalidStructureRequest refused) {
      throw invalid(method, refused);
    } catch (SQLException failure) {
      Log.error(
          "uma leitura de " + method + " da análise " + id.value() + " falhou no SQLite", failure);
      throw new RequestRejected(ErrorCodes.internalUnexpected(failure.getClass().getName()));
    } catch (IllegalStateException closedOrNested) {
      if (analyses.readable(id).isEmpty()) {
        throw new RequestRejected(ErrorCodes.analysisNotFound());
      }
      Log.error(
          "uma leitura de " + method + " da análise " + id.value() + " encontrou um defeito",
          closedOrNested);
      throw new RequestRejected(ErrorCodes.internalUnexpected(closedOrNested.getClass().getName()));
    }
  }

  static ReadableAnalysis ready(AnalysisRegistry analyses, AnalysisId id) {
    final ReadableAnalysis analysis =
        analyses.readable(id).orElseThrow(() -> new RequestRejected(ErrorCodes.analysisNotFound()));
    final boolean graphCompleted =
        analyses
            .status(id)
            .map(
                snapshot ->
                    snapshot.stages().get(AnalysisStage.STRUCTURAL_GRAPH).status()
                        == StageStatus.COMPLETED)
            .orElse(false);
    if (!graphCompleted) {
      throw new RequestRejected(ErrorCodes.analysisNotFound());
    }
    return analysis;
  }

  static CodeElementId elementId(String method, String field, String wire) {
    if (wire == null) {
      return null;
    }
    if (exceedsIdCeiling(wire)) {
      throw new RequestRejected(ErrorCodes.invalidPayload(method, field + ID_TOO_LONG));
    }
    try {
      return new CodeElementId(wire);
    } catch (IllegalArgumentException malformed) {
      throw new RequestRejected(ErrorCodes.invalidPayload(method, field + ID_MALFORMED));
    }
  }

  static <T> T required(String method, String field, T value) {
    if (value == null) {
      throw new RequestRejected(ErrorCodes.invalidPayload(method, field + FIELD_REQUIRED));
    }
    return value;
  }

  private static boolean exceedsIdCeiling(String wire) {
    long bytes = 0;
    for (int index = 0; index < wire.length(); ) {
      final int codePoint = wire.codePointAt(index);
      bytes += utf8Width(codePoint);
      if (bytes > AnalysisConstants.MAX_ELEMENT_ID_BYTES) {
        return true;
      }
      index += Character.charCount(codePoint);
    }
    return false;
  }

  private static int utf8Width(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }

  static RequestRejected invalid(String method, InvalidStructureRequest refused) {
    return new RequestRejected(ErrorCodes.invalidPayload(method, refused.detail()));
  }

  @FunctionalInterface
  interface StructureWork<T> {

    T run(Connection connection, long analysisPk) throws SQLException;
  }
}
