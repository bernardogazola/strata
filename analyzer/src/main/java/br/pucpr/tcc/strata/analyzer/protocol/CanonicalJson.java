package br.pucpr.tcc.strata.analyzer.protocol;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public final class CanonicalJson {

  private final ObjectMapper mapper;
  private final JsonFactory factory;

  public CanonicalJson() {
    this.mapper = JsonMapper.builder().build();
    this.factory =
        JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build()
            .setCodec(mapper);
  }

  public byte[] toBytes(Envelope envelope) {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
    try {
      writeTo(envelope, buffer);
    } catch (IOException impossible) {
      throw new UncheckedIOException("ByteArrayOutputStream não falha", impossible);
    }
    return buffer.toByteArray();
  }

  public void writeTo(Envelope envelope, OutputStream out) throws IOException {
    final JsonNode node = EnvelopeCodec.toNode(mapper, envelope);
    final JsonGenerator generator = factory.createGenerator(out, JsonEncoding.UTF8);
    writeNode(node, generator);
    generator.flush();
  }

  private static void writeNode(JsonNode node, JsonGenerator generator) throws IOException {
    switch (node.getNodeType()) {
      case OBJECT -> {
        final ObjectNode object = (ObjectNode) node;
        final List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        generator.writeStartObject();
        for (final String name : names) {
          generator.writeFieldName(name);
          writeNode(object.get(name), generator);
        }
        generator.writeEndObject();
      }
      case ARRAY -> {
        generator.writeStartArray();
        for (final JsonNode element : (ArrayNode) node) {
          writeNode(element, generator);
        }
        generator.writeEndArray();
      }
      case NUMBER -> {
        if (node.isIntegralNumber()) {
          generator.writeNumber(node.longValue());
        } else {
          generator.writeNumber(CanonicalNumbers.toJson(node.doubleValue()));
        }
      }
      case STRING -> generator.writeString(node.textValue());
      case BOOLEAN -> generator.writeBoolean(node.booleanValue());
      case NULL -> generator.writeNull();
      default ->
          throw new IllegalArgumentException("nó JSON não serializável: " + node.getNodeType());
    }
  }
}
