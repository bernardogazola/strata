package br.pucpr.tcc.strata.analyzer.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class EnvelopeCodec {

  private EnvelopeCodec() {}

  static ObjectNode toNode(ObjectMapper mapper, Envelope envelope) {
    final ObjectNode node = mapper.createObjectNode();
    node.put("protocol", envelope.protocol());
    switch (envelope) {
      case Envelope.Request request -> {
        node.put("kind", "request");
        node.put("id", request.id());
        node.put("method", request.method());
        node.set("payload", request.payload());
        if (request.repositoryPath() != null) {
          node.put("repositoryPath", request.repositoryPath());
        }
      }
      case Envelope.OkResponse response -> {
        node.put("kind", "response");
        node.put("id", response.id());
        node.put("ok", true);
        node.set("payload", mapper.valueToTree(response.payload()));
      }
      case Envelope.ErrorResponse response -> {
        node.put("kind", "response");
        node.put("id", response.id());
        node.put("ok", false);
        node.set("error", mapper.valueToTree(response.error()));
      }
      case Envelope.Event event -> {
        node.put("kind", "event");
        node.put("event", event.event());
        node.set("payload", mapper.valueToTree(event.payload()));
      }
    }
    return node;
  }
}
