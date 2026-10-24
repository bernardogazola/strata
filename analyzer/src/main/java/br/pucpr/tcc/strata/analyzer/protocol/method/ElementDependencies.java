package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.DependencyCursor;
import br.pucpr.tcc.strata.analyzer.structure.DependencyDirection;
import br.pucpr.tcc.strata.analyzer.structure.DependencyPageQuery;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class ElementDependencies implements MethodHandler<ElementDependencies.Params> {

  static final String METHOD = "element.dependencies";

  private final AnalysisRegistry analyses;

  public ElementDependencies(AnalysisRegistry analyses) {
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
    final String wireId = StructureReads.required(METHOD, "elementId", params.elementId());
    final DependencyDirection direction =
        StructureReads.required(METHOD, "direction", params.direction());
    final CodeElementId element = StructureReads.elementId(METHOD, "elementId", wireId);
    final int limit;
    final DependencyCursor after;
    try {
      DependencyPageQuery.requireElement(element);
      limit = DependencyPageQuery.pageSize(params.limit());
      after = params.cursor() == null ? null : DependencyCursor.decode(params.cursor());
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    return DependencyPageView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                DependencyPageQuery.page(
                    connection, analysisPk, element, direction, after, limit)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("cursor") String cursor,
      @JsonProperty(value = "direction", required = true) DependencyDirection direction,
      @JsonProperty(value = "elementId", required = true) String elementId,
      @JsonProperty("limit") Integer limit) {}
}
