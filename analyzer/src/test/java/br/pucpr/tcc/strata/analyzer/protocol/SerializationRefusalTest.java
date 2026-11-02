package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Lifecycle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class SerializationRefusalTest {

  private static final String ID = "req_01JQ8ZA1B2C3D4E5F6G7H8J9K0";

  private static String id(char lastIdChar) {
    return ID.substring(0, ID.length() - 1) + lastIdChar;
  }

  private static String request(char lastIdChar) {
    return "{\"id\":\""
        + id(lastIdChar)
        + "\",\"kind\":\"request\",\"method\":\"system.ping\",\"payload\":{},\"protocol\":1}\n";
  }

  private static String internalUnexpected(char lastIdChar) {
    return "{\"error\":{\"code\":\"INTERNAL_UNEXPECTED\","
        + "\"detail\":\"java.lang.IllegalArgumentException\",\"origin\":\"analyzer\","
        + "\"params\":{}},\"id\":\""
        + id(lastIdChar)
        + "\",\"kind\":\"response\",\"ok\":false,\"protocol\":1}\n";
  }

  private static String ok(char lastIdChar, String payload) {
    return "{\"id\":\""
        + id(lastIdChar)
        + "\",\"kind\":\"response\",\"ok\":true,\"payload\":"
        + payload
        + ",\"protocol\":1}\n";
  }

  private static final class CountingStream extends OutputStream {
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private int writes;
    private int flushes;

    @Override
    public void write(int b) {
      writes++;
      written.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      writes++;
      written.write(b, off, len);
    }

    @Override
    public void flush() {
      flushes++;
    }

    String text() {
      return written.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class ScriptedHandler implements MethodHandler<MethodHandler.EmptyParams> {
    private final Function<Envelope.Request, Object> answer;

    ScriptedHandler(Function<Envelope.Request, Object> answer) {
      this.answer = answer;
    }

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
      return answer.apply(request);
    }
  }

  private record Node(String id, double pagerank) {}

  private record Slice(List<Node> nodes) {}

  private static char last(Envelope.Request request) {
    return request.id().charAt(request.id().length() - 1);
  }

  private record Session(int exitCode, String stdout, String stderr) {}

  private static Session run(String input, MethodHandler<?> handler) throws IOException {
    final CountingStream out = new CountingStream();
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream original = System.err;
    final int exitCode;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      exitCode =
          new Lifecycle(
                  new JsonLines.Reader(
                      new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                      AnalysisConstants.MAX_FRAME_BYTES),
                  new JsonLines.Writer(out, new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES),
                  Dispatcher.of(JsonLines.strictInboundMapper(), List.of(handler)),
                  new AtomicBoolean(false),
                  AnalysisConstants.MALFORMED_LINE_TOLERANCE)
              .run();
    } finally {
      System.setErr(original);
    }
    return new Session(exitCode, out.text(), captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  void oEscritorRecusaAntesDoPrimeiroByte() {
    final CountingStream out = new CountingStream();
    final JsonLines.Writer writer =
        new JsonLines.Writer(out, new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES);
    final List<Envelope> refused =
        List.of(
            Envelope.ok(id('0'), Map.of("pagerank", Double.NaN)),
            Envelope.ok(id('1'), new Slice(List.of(new Node("type:a.A", Double.MIN_VALUE)))),
            Envelope.ok(id('2'), Map.of("pagerank", Double.POSITIVE_INFINITY)),
            new Envelope.Event(
                "analysis.progress",
                Map.of("ratio", Double.NaN),
                AnalysisConstants.PROTOCOL_VERSION));

    for (final Envelope envelope : refused) {
      assertThatThrownBy(() -> writer.emit(envelope))
          .as(envelope.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(out.writes).isZero();
    assertThat(out.flushes).isZero();
  }

  @Test
  void umaRespostaQueNaoSerializaViraInternalUnexpectedEOLacoSegue() throws IOException {
    final ScriptedHandler handler =
        new ScriptedHandler(
            request ->
                switch (last(request)) {
                  case '0' -> Map.of("pagerank", Double.NaN);
                  case '1' -> new Slice(List.of(new Node("type:a.A", 1e-310)));
                  case '2' -> new Slice(List.of(new Node("type:a.A", 1e-7)));
                  default -> Map.of("pagerank", 0.25);
                });

    final Session session = run(request('0') + request('1') + request('2') + request('3'), handler);

    assertThat(session.exitCode()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(session.stdout())
        .isEqualTo(
            internalUnexpected('0')
                + internalUnexpected('1')
                + ok('2', "{\"nodes\":[{\"id\":\"type:a.A\",\"pagerank\":1e-7}]}")
                + ok('3', "{\"pagerank\":0.25}"));
    assertThat(session.stderr())
        .contains("a resposta a " + id('0') + " não pode ser serializada")
        .contains("a resposta a " + id('1') + " não pode ser serializada")
        .doesNotContain("a resposta a " + id('2'));
  }

  @Test
  void umaRespostaDeErroQueNaoSerializaTambemViraInternalUnexpected() throws IOException {
    final ScriptedHandler handler =
        new ScriptedHandler(
            request -> {
              if (last(request) == '0') {
                throw new RequestRejected(
                    new ProtocolError(
                        ErrorCodes.PROTO_INVALID_PAYLOAD,
                        "fixed sentence",
                        "analyzer",
                        Map.of("ratio", Double.POSITIVE_INFINITY)));
              }
              return Map.of("pagerank", 0.25);
            });

    final Session session = run(request('0') + request('1'), handler);

    assertThat(session.exitCode()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(session.stdout())
        .isEqualTo(internalUnexpected('0') + ok('1', "{\"pagerank\":0.25}"));
    assertThat(session.stderr()).contains("a resposta a " + id('0') + " não pode ser serializada");
  }

  @Test
  void oGanchoNaoRodaQuandoARespostaFoiTrocada() throws IOException {
    final AtomicBoolean ran = new AtomicBoolean(false);
    final ScriptedHandler handler =
        new ScriptedHandler(
            request ->
                new MethodHandler.Reply(Map.of("pagerank", Double.NaN), () -> ran.set(true)));

    final Session session = run(request('0'), handler);

    assertThat(session.exitCode()).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(session.stdout()).isEqualTo(internalUnexpected('0'));
    assertThat(ran).isFalse();
  }
}
