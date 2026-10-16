package br.pucpr.tcc.strata.analyzer.protocol.method;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class RepositoryDescribeTest {

  private static final String ID = "req_01JQ8ZG1B2C3D4E5F6G7H8J9K0";

  private final ObjectMapper inbound = JsonLines.strictInboundMapper();
  private final Dispatcher dispatcher =
      Dispatcher.of(inbound, Handlers.all(() -> 0L, new AtomicBoolean(false)));

  private String despachar(String repositoryPath) throws IOException {
    final ObjectNode frame = inbound.createObjectNode();
    frame.put("id", ID).put("kind", "request").put("method", "repository.describe");
    frame.putObject("payload");
    frame.put("protocol", 1);
    if (repositoryPath != null) {
      frame.put("repositoryPath", repositoryPath);
    }
    final Dispatcher.Outcome fora =
        dispatcher.dispatch(
            dispatcher.parse(inbound.writeValueAsString(frame).getBytes(StandardCharsets.UTF_8)));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return new String(
        new CanonicalJson().toBytes(((Dispatcher.Outcome.Respond) fora).envelope()),
        StandardCharsets.UTF_8);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      nullValues = "AUSENTE",
      value = {
        "AUSENTE|repositoryPath é obrigatório",
        "repo|repositoryPath deve ser absoluto e normalizado",
        "/tmp/a/../b|repositoryPath deve ser absoluto e normalizado",
        "/tmp/./b|repositoryPath deve ser absoluto e normalizado"
      })
  void caminhoQueORustNuncaEscreveViraProtoInvalidPayloadSemEcoar(
      String repositoryPath, String detail) throws IOException {
    assertThat(despachar(repositoryPath))
        .isEqualTo(
            "{\"error\":{\"code\":\"PROTO_INVALID_PAYLOAD\",\"detail\":\""
                + detail
                + "\",\"origin\":\"analyzer\",\"params\":{\"method\":\"repository.describe\"}},"
                + "\"id\":\""
                + ID
                + "\",\"kind\":\"response\",\"ok\":false,\"protocol\":1}");
  }

  @Test
  void caminhoComNulViraProtoInvalidPayload() throws IOException {
    assertThat(despachar("/tmp/a" + '\0' + "b"))
        .contains("\"code\":\"PROTO_INVALID_PAYLOAD\"")
        .contains("\"detail\":\"repositoryPath não é um caminho válido\"");
  }
}
