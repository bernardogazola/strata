package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import br.pucpr.tcc.strata.analyzer.structure.SearchQuery;
import br.pucpr.tcc.strata.analyzer.structure.SearchTerm;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    final AnalysisId id = AnalysisCancel.analysisId(METHOD, params.analysisId());
    final Optional<SearchTerm> term;
    final Set<ElementKind> kinds;
    final int limit;
    try {
      term = SearchTerm.of(params.query());
      kinds = SearchQuery.kinds(params.kinds());
      limit = SearchQuery.resultLimit(params.limit());
    } catch (InvalidStructureRequest refused) {
      throw StructureReads.invalid(METHOD, refused);
    }
    StructureReads.ready(analyses, id);
    if (term.isEmpty() || kinds.isEmpty()) {
      return SearchResultView.NONE;
    }
    final SearchTerm folded = term.get();
    return SearchResultView.of(
        StructureReads.read(
            analyses,
            METHOD,
            id,
            (connection, analysisPk) ->
                SearchQuery.search(connection, analysisPk, folded, kinds, limit)));
  }

  public record Params(
      @JsonProperty("analysisId") String analysisId,
      @JsonProperty("query") String query,
      @JsonProperty("kinds") List<ElementKind> kinds,
      @JsonProperty("limit") Integer limit) {}
}
