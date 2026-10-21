package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Lifecycle;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemPing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class AfterEmitHookTest {

  private static final String ID = "req_01JQ8ZA1B2C3D4E5F6G7H8J9K0";

  private static String request(char lastIdChar) {
    return "{\"id\":\""
        + ID.substring(0, ID.length() - 1)
        + lastIdChar
        + "\",\"kind\":\"request\",\"method\":\"system.ping\",\"payload\":{},\"protocol\":1}";
  }

  private static final class FlushTrackingStream extends OutputStream {
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private int flushedBytes;

    @Override
    public void write(int b) {
      written.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      written.write(b, off, len);
    }

    @Override
    public void flush() {
      flushedBytes = written.size();
    }

    String flushedText() {
      return new String(written.toByteArray(), 0, flushedBytes, StandardCharsets.UTF_8);
    }

    String writtenText() {
      return written.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class HandlerComGancho implements MethodHandler<MethodHandler.EmptyParams> {
    private final Function<Envelope.Request, Object> resposta;

    HandlerComGancho(Function<Envelope.Request, Object> resposta) {
      this.resposta = resposta;
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
      return resposta.apply(request);
    }
  }

  private static int run(
      String input, int maxFrameBytes, MethodHandler<?> handler, OutputStream out)
      throws IOException {
    return new Lifecycle(
            new JsonLines.Reader(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), maxFrameBytes),
            new JsonLines.Writer(out, new CanonicalJson(), maxFrameBytes),
            Dispatcher.of(JsonLines.strictInboundMapper(), List.of(handler)),
            new AtomicBoolean(false),
            AnalysisConstants.MALFORMED_LINE_TOLERANCE)
        .run();
  }

  @Test
  void oGanchoRodaDepoisDeARespostaEstarEscritaEDescarregada() throws IOException {
    final FlushTrackingStream out = new FlushTrackingStream();
    final List<String> vistoPeloGancho = new ArrayList<>();
    final HandlerComGancho handler =
        new HandlerComGancho(
            request ->
                new MethodHandler.Reply(
                    Map.of("uptimeMillis", 0), () -> vistoPeloGancho.add(out.flushedText())));

    final int saida =
        run(
            request('0') + "\n" + request('1') + "\n",
            AnalysisConstants.MAX_FRAME_BYTES,
            handler,
            out);

    assertThat(saida).isEqualTo(AnalysisConstants.EXIT_OK);
    final List<String> linhas = out.writtenText().lines().toList();
    assertThat(linhas).hasSize(2);
    assertThat(vistoPeloGancho)
        .containsExactly(linhas.get(0) + "\n", linhas.get(0) + "\n" + linhas.get(1) + "\n");
  }

  @Test
  void aRespostaComGanchoTemOsMesmosBytesQueASemGancho() throws IOException {
    final ByteArrayOutputStream comGancho = new ByteArrayOutputStream();
    final ByteArrayOutputStream semGancho = new ByteArrayOutputStream();

    run(
        request('0') + "\n",
        AnalysisConstants.MAX_FRAME_BYTES,
        new HandlerComGancho(
            request -> new MethodHandler.Reply(Map.of("uptimeMillis", 7), () -> {})),
        comGancho);
    run(
        request('0') + "\n",
        AnalysisConstants.MAX_FRAME_BYTES,
        new SystemPing(() -> 7L),
        semGancho);

    assertThat(comGancho.toString(StandardCharsets.UTF_8))
        .isEqualTo(semGancho.toString(StandardCharsets.UTF_8))
        .contains("\"payload\":{\"uptimeMillis\":7}");
  }

  @Test
  void oGanchoNaoRodaQuandoARespostaFoiTrocadaPeloErroDeTamanho() throws IOException {
    final int teto = 1024;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final AtomicBoolean rodou = new AtomicBoolean(false);
    final HandlerComGancho handler =
        new HandlerComGancho(
            request ->
                new MethodHandler.Reply(Map.of("blob", "x".repeat(4096)), () -> rodou.set(true)));

    assertThat(run(request('0') + "\n", teto, handler, out)).isEqualTo(AnalysisConstants.EXIT_OK);

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("\"code\":\"PROTO_MESSAGE_TOO_LARGE\"");
    assertThat(rodou).isFalse();
  }

  @Test
  void umGanchoQueLancaNaoDerrubaOLaco() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<String> respondidos = new ArrayList<>();
    final HandlerComGancho handler =
        new HandlerComGancho(
            request -> {
              respondidos.add(request.id());
              return new MethodHandler.Reply(
                  Map.of("uptimeMillis", 0),
                  () -> {
                    throw new IllegalStateException("gancho quebrado");
                  });
            });

    final int saida =
        run(
            request('0') + "\n" + request('1') + "\n",
            AnalysisConstants.MAX_FRAME_BYTES,
            handler,
            out);

    assertThat(saida).isEqualTo(AnalysisConstants.EXIT_OK);
    assertThat(respondidos).hasSize(2);
    assertThat(out.toString(StandardCharsets.UTF_8).lines())
        .hasSize(2)
        .allMatch(l -> l.contains("\"ok\":true"));
  }

  @Test
  void oDespachanteDesembrulhaOReplyECarregaOGancho() throws IOException {
    final Runnable gancho = () -> {};
    final Dispatcher comGancho =
        Dispatcher.of(
            JsonLines.strictInboundMapper(),
            List.of(
                new HandlerComGancho(request -> new MethodHandler.Reply(Map.of("a", 1), gancho))));
    final Dispatcher semGancho =
        Dispatcher.of(JsonLines.strictInboundMapper(), List.of(new SystemPing(() -> 0L)));
    final byte[] linha = request('0').getBytes(StandardCharsets.UTF_8);

    final Dispatcher.Outcome.Respond com =
        (Dispatcher.Outcome.Respond) comGancho.dispatch(comGancho.parse(linha));
    final Dispatcher.Outcome.Respond sem =
        (Dispatcher.Outcome.Respond) semGancho.dispatch(semGancho.parse(linha));

    assertThat(com.afterEmit()).isSameAs(gancho);
    assertThat(((Envelope.OkResponse) com.envelope()).payload()).isEqualTo(Map.of("a", 1));
    assertThat(sem.afterEmit()).isSameAs(Dispatcher.Outcome.Respond.NOTHING);
  }
}
