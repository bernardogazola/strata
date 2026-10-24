package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.ElementDetailQuery;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class ElementDescribe implements MethodHandler<ElementDescribe.Params> {

  static final String METHOD = "element.describe";

  private final AnalysisRegistry analyses;

  public ElementDescribe(AnalysisRegistry analyses) {
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
    final CodeElementId element = StructureReads.elementId(METHOD, "elementId", wireId);
    try {
      ElementDetailQuery.requireElement(element);
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    return ElementDetailView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                ElementDetailQuery.describe(connection, analysisPk, element)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty(value = "elementId", required = true) String elementId) {}
}
