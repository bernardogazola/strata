package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Lifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class FramingTest {

  private static final String ID = "req_01JQ8ZA1B2C3D4E5F6G7H8J9K0";

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String pingRequest(char lastIdChar) {
    return "{\"id\":\""
        + ID.substring(0, ID.length() - 1)
        + lastIdChar
        + "\",\"kind\":\"request\",\"method\":\"system.ping\",\"payload\":{},\"protocol\":1}";
  }

  private static final class Harness {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Lifecycle lifecycle;

    Harness(byte[] input, int maxFrameBytes, List<MethodHandler<?>> handlers) {
      final ObjectMapper inbound = JsonLines.strictInboundMapper();
      final Dispatcher dispatcher = Dispatcher.of(inbound, handlers);
      this.lifecycle =
          new Lifecycle(
              new JsonLines.Reader(new ByteArrayInputStream(input), maxFrameBytes),
              new JsonLines.Writer(out, new CanonicalJson(), maxFrameBytes),
              dispatcher,
              shutdown,
              AnalysisConstants.MALFORMED_LINE_TOLERANCE);
    }

    int run() throws IOException {
      return lifecycle.run();
    }

    String written() {
      return out.toString(StandardCharsets.UTF_8);
    }

    int malformed() {
      return lifecycle.malformedCount();
    }
  }

  private static Harness harness(String input) {
    return new Harness(
        utf8(input), AnalysisConstants.MAX_FRAME_BYTES, List.of(new SystemPing(() -> 0L)));
  }

  private record Volumoso(String blob) {}

  private static final class HandlerVolumoso implements MethodHandler<MethodHandler.EmptyParams> {
    @Override
    public String method() {
      return "system.ping";
    }

    @Override
    public Class<MethodHandler.EmptyParams> paramsType() {
      return MethodHandler.EmptyParams.class;
    }

    @Override
    public Object handle(MethodHandler.EmptyParams params, Envelope.Request request) {
      return new Volumoso("x".repeat(4096));
    }
  }

  @Test
  void quadroAcimaDoTetoViraRespostaDeErroENuncaUmaEmissaoAcimaDoTeto() throws IOException {
    final int teto = 1024;
    final Harness h =
        new Harness(utf8(pingRequest('0') + "\n"), teto, List.of(new HandlerVolumoso()));

    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_OK);

    final String saida = h.written();
    assertThat(saida.lines()).hasSize(1);
    assertThat(saida)
        .contains("\"ok\":false")
        .contains("\"code\":\"PROTO_MESSAGE_TOO_LARGE\"")
        .contains("\"origin\":\"analyzer\"")
        .contains("\"limitBytes\":" + teto)
        .contains("\"receivedBytes\":");
    assertThat(saida.getBytes(StandardCharsets.UTF_8).length).isLessThan(teto);
    assertThat(h.malformed()).isZero();
  }

  @Test
  void oTetoDeProducaoEODeclaradoEmAnalysisConstants() {
    assertThat(AnalysisConstants.MAX_FRAME_BYTES).isEqualTo(16_777_216);
  }

  @Test
  void cincoLinhasMalformadasSaoToleradasEResincronizadasEASextaEncerra() throws IOException {
    final String entrada =
        String.join(
            "\n",
            "{isto nao e json",
            pingRequest('0'),
            "{\"id\":\"" + ID + "\",\"kind\":\"request\",\"method\":\"system.ping\","
                + "\"payload\":{},\"protocol\":2}",
            pingRequest('1'),
            "{\"id\":\"" + ID + "\",\"kind\":\"event\",\"event\":\"protocol.ready\","
                + "\"payload\":{},\"protocol\":1}",
            pingRequest('2'),
            "{\"id\":\"nao-e-um-req-id\",\"kind\":\"request\",\"method\":\"system.ping\","
                + "\"payload\":{},\"protocol\":1}",
            pingRequest('3'),
            "[\"array no lugar de objeto\"]",
            pingRequest('4'))
            + "\n";

    final Harness h = harness(entrada);
    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(h.malformed()).isEqualTo(AnalysisConstants.MALFORMED_LINE_TOLERANCE);

    final List<String> respostas = h.written().lines().toList();
    assertThat(respostas).hasSize(5);
    for (int i = 0; i < 5; i++) {
      assertThat(respostas.get(i)).contains("K" + i + "\"").contains("\"ok\":true");
    }
  }

  @Test
  void aSextaLinhaMalformadaEncerraOProcesso() throws IOException {
    final StringBuilder entrada = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      entrada.append("{isto nao e json\n");
    }
    entrada.append(pingRequest('0')).append('\n');

    final Harness h = harness(entrada.toString());
    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED);
    assertThat(h.malformed()).isEqualTo(AnalysisConstants.MALFORMED_LINE_TOLERANCE + 1);
    assertThat(h.written()).isEmpty();
  }

  @Test
  void umCarriageReturnSolitarioNaoParteOQuadro_noNivelDoLeitor() throws IOException {
    final byte[] entrada = utf8(pingRequest('0').replace(",\"payload\"", "\r,\"payload\"") + "\n");
    final JsonLines.Reader leitor =
        new JsonLines.Reader(new ByteArrayInputStream(entrada), AnalysisConstants.MAX_FRAME_BYTES);

    final JsonLines.ReadResult primeiro = leitor.read();
    assertThat(primeiro).isInstanceOf(JsonLines.ReadResult.Frame.class);
    final byte[] quadro = ((JsonLines.ReadResult.Frame) primeiro).bytes();
    assertThat(quadro).hasSize(entrada.length - 1).contains((byte) 0x0D);
    assertThat(leitor.read()).isInstanceOf(JsonLines.ReadResult.EndOfStream.class);
  }

  @Test
  void umCarriageReturnEntreTokensNaoParteOQuadro_eNaoContaComoMalformado() throws IOException {
    final Harness h = harness(pingRequest('0').replace(",\"payload\"", "\r,\"payload\"") + "\n");
    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(h.written().lines()).hasSize(1);
    assertThat(h.malformed()).isZero();
  }

  @Test
  void umCarriageReturnDentroDeUmaStringContaUMAVezSoENaoDuas() throws IOException {
    final Harness h =
        harness(pingRequest('0').replace("system.ping", "system\r.ping") + "\n" + pingRequest('1')
            + "\n");
    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(h.malformed()).isEqualTo(1);
    assertThat(h.written().lines()).hasSize(1);
  }

  @Test
  void umTerminadorCRLFNaoContaComoMalformado() throws IOException {
    final Harness h = harness(pingRequest('0') + "\r\n");
    assertThat(h.run()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(h.malformed()).isZero();
    assertThat(h.written().lines()).hasSize(1);
  }
}
