package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.GraphLevel;
import br.pucpr.tcc.strata.analyzer.structure.GraphSliceQuery;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import br.pucpr.tcc.strata.analyzer.structure.SliceBudget;
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
    final AnalysisId id = AnalysisCancel.analysisId(METHOD, params.analysisId());
    final CodeElementId parent = StructureReads.elementId(METHOD, "parentId", params.parentId());
    final GraphLevel level = params.level();
    final SliceBudget budget;
    try {
      budget = SliceBudget.of(level, params.limit());
      GraphSliceQuery.requireParent(level, parent);
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    return GraphSliceView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                GraphSliceQuery.slice(connection, analysisPk, level, parent, budget)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("level") GraphLevel level,
      @JsonProperty("parentId") String parentId,
      @JsonProperty("limit") Integer limit) {}
}
