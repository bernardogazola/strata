package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.structure.GraphLevel;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class GraphSlice implements MethodHandler<GraphSlice.Params> {

  static final String METHOD = "graph.slice";

  private final AnalysisRegistry analyses;

  public GraphSlice(AnalysisRegistry analyses) {
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
    throw new UnsupportedOperationException("ainda não implementado: graph.slice");
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("level") GraphLevel level,
      @JsonProperty("parentId") String parentId,
      @JsonProperty("limit") Integer limit) {}
}
