package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisConfiguration;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.analysis.StartResult;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class AnalysisStart implements MethodHandler<AnalysisStart.Params> {

  static final String METHOD = "analysis.start";

  private final AnalysisRegistry analyses;

  public AnalysisStart(AnalysisRegistry analyses) {
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
    final AnalysisConfiguration configuration = configuration(params);
    final Path claimed = ClaimedRoot.of(METHOD, request.repositoryPath());
    return switch (analyses.start(request.repositoryPath(), claimed, configuration)) {
      case StartResult.Started started ->
          new MethodHandler.Reply(
              new Accepted(started.analysisId().value(), false), started.launch());
      case StartResult.AlreadyRunning _ ->
          throw new RequestRejected(ErrorCodes.analysisAlreadyRunning());
    };
  }

  private static AnalysisConfiguration configuration(Params params) {
    try {
      return AnalysisConfiguration.normalized(
          params.includeTests(),
          params.historyScope(),
          params.historyScopeCommits(),
          params.sourceRootIds(),
          params.rebuildCache());
    } catch (IllegalArgumentException refused) {
      throw new RequestRejected(ErrorCodes.invalidPayload(METHOD, refused.getMessage()));
    }
  }

  public record Params(
      @JsonProperty("includeTests") Boolean includeTests,
      @JsonProperty("historyScope") HistoryScope historyScope,
      @JsonProperty("historyScopeCommits") Long historyScopeCommits,
      @JsonProperty("sourceRootIds") List<String> sourceRootIds,
      @JsonProperty("rebuildCache") Boolean rebuildCache) {}

  public record Accepted(String analysisId, boolean reusedCache) {}
}
