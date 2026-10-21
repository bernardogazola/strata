package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import java.util.Objects;

public final class AnalysisStatusQuery implements MethodHandler<AnalysisStatusQuery.Params> {

  static final String METHOD = "analysis.status";

  private final AnalysisRegistry analyses;

  public AnalysisStatusQuery(AnalysisRegistry analyses) {
    this.analyses = Objects.requireNonNull(analyses, "analyses");
  }

  @Override
  public String method() {
    return METHOD;
  }

  @Override
  public Class<Params> paramsType() {
    return Params.class;
  }

  @Override
  public Object handle(Params params, Envelope.Request request) {
    return analyses
        .status(AnalysisCancel.analysisId(METHOD, params.analysisId()))
        .map(AnalysisStatusReport::of)
        .orElseThrow(() -> new RequestRejected(ErrorCodes.analysisNotFound()));
  }

  public record Params(String analysisId) {}
}
