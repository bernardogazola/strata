package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DispatcherErrorPathTest {

  private static final String ID = "req_01JQ8ZD1B2C3D4E5F6G7H8J9K0";
  private static final ProtocolError RECUSA =
      new ProtocolError("REPO_IS_BARE", "fixed english detail", "analyzer", Map.of());

  private static MethodHandler<MethodHandler.EmptyParams> handler(String method, Runnable body) {
    return new MethodHandler<>() {
      @Override
      public String method() {
        return method;
      }

      @Override
      public Class<MethodHandler.EmptyParams> paramsType() {
        return MethodHandler.EmptyParams.class;
      }

      @Override
      public Object handle(MethodHandler.EmptyParams params, Envelope.Request request) {
        body.run();
        return Map.of();
      }
    };
  }

  private static String despachar(MethodHandler<?> handler) throws IOException {
    final Dispatcher dispatcher = Dispatcher.of(JsonLines.strictInboundMapper(), List.of(handler));
    final String frame =
        "{\"id\":\""
            + ID
            + "\",\"kind\":\"request\",\"method\":\""
            + handler.method()
            + "\",\"payload\":{},\"protocol\":1}";
    final Dispatcher.Outcome outcome =
        dispatcher.dispatch(dispatcher.parse(frame.getBytes(StandardCharsets.UTF_8)));
    assertThat(outcome).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return new String(
        new CanonicalJson().toBytes(((Dispatcher.Outcome.Respond) outcome).envelope()),
        StandardCharsets.UTF_8);
  }

  @Test
  void aRecusaTipadaViraRespostaComExatamenteAqueleErro() throws IOException {
    final String emitido =
        despachar(
            handler(
                "repository.describe",
                () -> {
                  throw new RequestRejected(RECUSA);
                }));

    assertThat(emitido)
        .isEqualTo(
            "{\"error\":{\"code\":\"REPO_IS_BARE\",\"detail\":\"fixed english detail\","
                + "\"origin\":\"analyzer\",\"params\":{}},\"id\":\""
                + ID
                + "\",\"kind\":\"response\",\"ok\":false,\"protocol\":1}");
  }

  @Test
  void oDefeitoImprevistoNaoLevaAMensagemDaExcecaoParaOFio() throws IOException {
    final String caminhoAbsoluto = "/Users/alguem/projetos/segredo/pom.xml";
    final String emitido =
        despachar(
            handler(
                "repository.describe",
                () -> {
                  throw new IllegalStateException(
                      new NoSuchFileException(caminhoAbsoluto).getMessage());
                }));

    assertThat(emitido)
        .doesNotContain("/Users")
        .contains("\"code\":\"INTERNAL_UNEXPECTED\"")
        .contains("\"detail\":\"java.lang.IllegalStateException\"");
  }
}
