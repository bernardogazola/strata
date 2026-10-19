package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidationReason;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public sealed interface Envelope {

  int protocol();

  record Request(String id, String method, JsonNode payload, int protocol, String repositoryPath)
      implements Envelope {}

  record OkResponse(String id, Object payload, int protocol) implements Envelope {}

  record ErrorResponse(String id, ProtocolError error, int protocol) implements Envelope {}

  record Event(String event, Object payload, int protocol) implements Envelope {}

  static OkResponse ok(String id, Object payload) {
    return new OkResponse(id, payload, AnalysisConstants.PROTOCOL_VERSION);
  }

  static ErrorResponse error(String id, ProtocolError error) {
    return new ErrorResponse(id, error, AnalysisConstants.PROTOCOL_VERSION);
  }

  static Event protocolReady(String analyzerVersion) {
    return new Event(
        "protocol.ready",
        Map.of(
            "analyzerVersion",
            analyzerVersion,
            "capabilities",
            List.of(),
            "protocol",
            AnalysisConstants.PROTOCOL_VERSION),
        AnalysisConstants.PROTOCOL_VERSION);
  }

  static Event cacheInvalidated(CacheInvalidation invalidation) {
    final ProtocolError error =
        switch (invalidation) {
          case CacheInvalidation.Corrupt _ -> ErrorCodes.cacheCorrupt();
          case CacheInvalidation.SchemaVersion(int fromVersion, int toVersion) ->
              ErrorCodes.cacheMigrationFailed(fromVersion, toVersion);
          case CacheInvalidation.Outdated _ -> null;
        };
    return new Event(
        "cache.invalidated",
        new CacheInvalidatedPayload(error, invalidation.reason()),
        AnalysisConstants.PROTOCOL_VERSION);
  }

  record CacheInvalidatedPayload(ProtocolError error, CacheInvalidationReason reason) {}
}
