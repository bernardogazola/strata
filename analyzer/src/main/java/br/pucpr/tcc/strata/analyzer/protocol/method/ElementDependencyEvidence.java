package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.structure.EvidenceQuery;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class ElementDependencyEvidence
    implements MethodHandler<ElementDependencyEvidence.Params> {

  static final String METHOD = "element.dependencyEvidence";

  private final AnalysisRegistry analyses;

  public ElementDependencyEvidence(AnalysisRegistry analyses) {
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
    final String wireSource = StructureReads.required(METHOD, "source", params.source());
    final String wireTarget = StructureReads.required(METHOD, "target", params.target());
    final CodeElementId source = StructureReads.elementId(METHOD, "source", wireSource);
    final CodeElementId target = StructureReads.elementId(METHOD, "target", wireTarget);
    final int limit;
    try {
      EvidenceQuery.requireEnds(source, target);
      limit = EvidenceQuery.resultLimit(params.limit());
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    return EvidenceResultView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                EvidenceQuery.evidence(connection, analysisPk, source, target, limit)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("limit") Integer limit,
      @JsonProperty(value = "source", required = true) String source,
      @JsonProperty(value = "target", required = true) String target) {}
}
