package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class OutlineChildren implements MethodHandler<OutlineChildren.Params> {

  static final String METHOD = "outline.children";

  private final AnalysisRegistry analyses;

  public OutlineChildren(AnalysisRegistry analyses) {
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
    throw new UnsupportedOperationException("ainda não implementado: outline.children");
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty(value = "parentId", required = true) String parentId,
      @JsonProperty("cursor") String cursor,
      @JsonProperty("limit") Integer limit) {}
}
