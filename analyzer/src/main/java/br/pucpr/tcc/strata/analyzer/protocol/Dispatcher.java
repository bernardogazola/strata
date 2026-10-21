package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class Dispatcher {

  private static final Pattern REQUEST_ID = Pattern.compile("^req_[0-9A-HJKMNP-TV-Z]{26}$");

  private static final Set<String> ENVELOPE_KEYS =
      Set.of("id", "kind", "method", "payload", "protocol", "repositoryPath");

  private final ObjectMapper inbound;
  private final Map<String, MethodHandler<?>> handlers;

  private Dispatcher(ObjectMapper inbound, Map<String, MethodHandler<?>> handlers) {
    this.inbound = inbound;
    this.handlers = handlers;
  }

  public static Dispatcher of(ObjectMapper inbound, List<MethodHandler<?>> handlers) {
    final Map<String, MethodHandler<?>> byName = new LinkedHashMap<>();
    for (final MethodHandler<?> handler : handlers) {
      if (byName.put(handler.method(), handler) != null) {
        throw new IllegalArgumentException("método registrado duas vezes: " + handler.method());
      }
    }
    return new Dispatcher(inbound, Collections.unmodifiableMap(byName));
  }

  public Set<String> registeredMethods() {
    return handlers.keySet();
  }

  public JsonNode parse(byte[] frame) throws IOException {
    return inbound.readTree(frame);
  }

  public Outcome dispatch(JsonNode frame) {
    if (frame == null || !frame.isObject()) {
      return new Outcome.Discard(DiscardReason.MALFORMED_FRAME);
    }
    final JsonNode protocol = frame.get("protocol");
    if (protocol == null
        || !protocol.isIntegralNumber()
        || protocol.intValue() != AnalysisConstants.PROTOCOL_VERSION) {
      return new Outcome.Discard(DiscardReason.PROTOCOL_MISMATCH);
    }
    final JsonNode kind = frame.get("kind");
    if (kind == null || !"request".equals(kind.textValue())) {
      return new Outcome.Discard(DiscardReason.WRONG_KIND);
    }
    final JsonNode id = frame.get("id");
    if (id == null || id.textValue() == null || !REQUEST_ID.matcher(id.textValue()).matches()) {
      return new Outcome.Discard(DiscardReason.UNCORRELATABLE_ID);
    }

    final JsonNode methodNode = frame.get("method");
    final String method = methodNode == null ? null : methodNode.textValue();
    if (method == null) {
      return new Outcome.Discard(DiscardReason.MALFORMED_FRAME);
    }

    for (final String key : (Iterable<String>) () -> frame.fieldNames()) {
      if (!ENVELOPE_KEYS.contains(key)) {
        return respondError(
            id.textValue(),
            ErrorCodes.invalidPayload(
                method, "propriedade desconhecida no envelope \"" + key + "\""));
      }
    }

    final MethodHandler<?> handler = handlers.get(method);
    if (handler == null) {
      return respondError(id.textValue(), ErrorCodes.unknownMethod(method));
    }

    final JsonNode payload = frame.get("payload");
    if (payload == null || !payload.isObject()) {
      return respondError(
          id.textValue(), ErrorCodes.invalidPayload(method, "o payload deve ser um objeto"));
    }

    final JsonNode repositoryPath = frame.get("repositoryPath");
    final Envelope.Request request =
        new Envelope.Request(
            id.textValue(),
            method,
            payload,
            AnalysisConstants.PROTOCOL_VERSION,
            repositoryPath == null ? null : repositoryPath.textValue());

    try {
      final Object result = invoke(handler, request);
      return result instanceof MethodHandler.Reply(Object replied, Runnable afterResponse)
          ? new Outcome.Respond(Envelope.ok(request.id(), replied), afterResponse)
          : new Outcome.Respond(Envelope.ok(request.id(), result));
    } catch (UnrecognizedPropertyException unknown) {
      return respondError(
          request.id(),
          ErrorCodes.invalidPayload(
              method, "propriedade desconhecida \"" + unknown.getPropertyName() + "\""));
    } catch (IOException invalid) {
      return respondError(
          request.id(), ErrorCodes.invalidPayload(method, "o payload não segue o schema"));
    } catch (RequestRejected rejected) {
      return respondError(request.id(), rejected.error());
    } catch (RuntimeException unexpected) {
      // No fio vai só o nome da classe; a mensagem, que pode ter caminho absoluto, fica no stderr.
      Log.error("o handler de " + method + " falhou", unexpected);
      return respondError(
          request.id(), ErrorCodes.internalUnexpected(unexpected.getClass().getName()));
    }
  }

  private <P> Object invoke(MethodHandler<P> handler, Envelope.Request request) throws IOException {
    final P params = inbound.treeToValue(request.payload(), handler.paramsType());
    return handler.handle(params, request);
  }

  private static Outcome respondError(String id, ProtocolError error) {
    return new Outcome.Respond(Envelope.error(id, error));
  }

  public sealed interface Outcome {

    record Respond(Envelope envelope, Runnable afterEmit) implements Outcome {

      public static final Runnable NOTHING = () -> {};

      public Respond {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(afterEmit, "afterEmit");
      }

      public Respond(Envelope envelope) {
        this(envelope, NOTHING);
      }
    }

    record Discard(DiscardReason reason) implements Outcome {}
  }

  public enum DiscardReason {
    PROTOCOL_MISMATCH,
    WRONG_KIND,
    UNCORRELATABLE_ID,
    MALFORMED_FRAME
  }
}
