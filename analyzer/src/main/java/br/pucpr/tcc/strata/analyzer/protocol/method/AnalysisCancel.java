package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import java.util.Objects;

public final class AnalysisCancel implements MethodHandler<AnalysisCancel.Params> {

  static final String METHOD = "analysis.cancel";

  private final AnalysisRegistry analyses;

  public AnalysisCancel(AnalysisRegistry analyses) {
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
    final AnalysisRegistry.CancelResult result =
        analyses.cancel(analysisId(METHOD, params.analysisId()));
    return new MethodHandler.Reply(new Cancelled(result.cancelled()), result.afterResponse());
  }

  static AnalysisId analysisId(String method, String wire) {
    try {
      return AnalysisId.parse(wire);
    } catch (IllegalArgumentException malformed) {
      throw new RequestRejected(ErrorCodes.invalidPayload(method, malformed.getMessage()));
    }
  }

  public record Params(String analysisId) {}

  public record Cancelled(boolean cancelled) {}
}
