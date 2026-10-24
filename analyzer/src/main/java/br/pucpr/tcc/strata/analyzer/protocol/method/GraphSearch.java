package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public final class GraphSearch implements MethodHandler<GraphSearch.Params> {

  static final String METHOD = "graph.search";

  private final AnalysisRegistry analyses;

  public GraphSearch(AnalysisRegistry analyses) {
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
    throw new UnsupportedOperationException("ainda não implementado: graph.search");
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("query") String query,
      @JsonProperty("kinds") List<ElementKind> kinds,
      @JsonProperty("limit") Integer limit) {}
}
