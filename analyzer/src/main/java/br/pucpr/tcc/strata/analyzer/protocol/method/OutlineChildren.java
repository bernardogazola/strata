package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import br.pucpr.tcc.strata.analyzer.structure.OutlineCursor;
import br.pucpr.tcc.strata.analyzer.structure.OutlineQuery;
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
    final AnalysisId id = AnalysisCancel.analysisId(METHOD, params.analysisId());
    final CodeElementId parent = StructureReads.elementId(METHOD, "parentId", params.parentId());
    final int limit;
    final OutlineCursor after;
    try {
      OutlineQuery.requireParent(parent);
      limit = OutlineQuery.pageSize(params.limit());
      after = params.cursor() == null ? null : OutlineCursor.decode(params.cursor());
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    return OutlinePageView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                OutlineQuery.page(connection, analysisPk, parent, after, limit)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty(value = "parentId", required = true) String parentId,
      @JsonProperty("cursor") String cursor,
      @JsonProperty("limit") Integer limit) {}
}
