package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemPing;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemShutdown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ProtocolFixtureTest {

  private static final Path FIXTURES =
      Path.of(
          Optional.ofNullable(System.getProperty("strata.protocol.fixtures"))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "-Dstrata.protocol.fixtures não foi definido; veja o surefire no pom")));

  private static final String VERSAO_DA_FIXTURE = "0.0.0-fixture";

  private static final Set<String> DIRIGIDOS =
      Set.of(
          "protocol.ready.mvp.jsonl",
          "protocol.discarded-frame.jsonl",
          "protocol.unknown-method.jsonl",
          "system.ping.ok.jsonl",
          "system.shutdown.ok.jsonl");

  private static final Map<String, String> EXPECTADOS_NAO_DIRIGIVEIS = criarBaseline();

  private static Map<String, String> criarBaseline() {
    final Map<String, String> m = new LinkedHashMap<>();
    m.put("protocol.invalid-payload.jsonl", "ainda não implementado");
    m.put("analysis.started.ok.jsonl", "ainda não implementado");
    m.put("analysis.progress.known-total.jsonl", "ainda não implementado");
    m.put("analysis.progress.unknown-total.jsonl", "ainda não implementado");
    m.put("analysis.stage.completed.ok.jsonl", "ainda não implementado");
    m.put("analysis.completed.partial.jsonl", "ainda não implementado");
    m.put("analysis.cancelled.ok.jsonl", "ainda não implementado");
    m.put("analysis.failed.no-java.jsonl", "ainda não implementado");
    m.put("analysis.warning.parse-failed.jsonl", "ainda não implementado");
    m.put("cache.invalidated.head-changed.jsonl", "ainda não implementado");
    m.put("graph.search.hit.jsonl", "ainda não implementado");
    m.put("graph.slice.package.jsonl", "ainda não implementado");
    m.put("graph.slice.type.jsonl", "ainda não implementado");
    m.put("outline.children.root.jsonl", "ainda não implementado");
    return Map.copyOf(m);
  }

  private static List<String> linhas(String arquivo) throws IOException {
    final String texto = Files.readString(FIXTURES.resolve(arquivo), StandardCharsets.UTF_8);
    return texto.lines().filter(l -> !l.isEmpty()).toList();
  }

  private static Set<String> arquivos() throws IOException {
    try (var s = Files.list(FIXTURES)) {
      return s.map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".jsonl"))
          .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
  }

  private final AtomicBoolean encerrar = new AtomicBoolean(false);
  private final ObjectMapper inbound = JsonLines.strictInboundMapper();
  private final Dispatcher dispatcher =
      Dispatcher.of(inbound, List.of(new SystemPing(() -> 0L), new SystemShutdown(encerrar)));
  private final CanonicalJson canonical = new CanonicalJson();

  private String emitido(Envelope envelope) {
    return new String(canonical.toBytes(envelope), StandardCharsets.UTF_8);
  }

  private Dispatcher.Outcome despachar(String linha) throws IOException {
    return dispatcher.dispatch(dispatcher.parse(linha.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aPastaDeFixturesExisteEFoiLida() throws IOException {
    assertThat(FIXTURES).isDirectory();
    assertThat(arquivos()).contains("protocol.ready.mvp.jsonl").hasSize(19);
  }

  @Test
  void protocolReadyBateByteAByte() throws IOException {
    final String esperado = linhas("protocol.ready.mvp.jsonl").get(0);
    assertThat(emitido(Envelope.protocolReady(VERSAO_DA_FIXTURE))).isEqualTo(esperado);
  }

  @Test
  void systemPingBateByteAByte() throws IOException {
    final List<String> l = linhas("system.ping.ok.jsonl");
    final Dispatcher.Outcome fora = despachar(l.get(0));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    assertThat(emitido(((Dispatcher.Outcome.Respond) fora).envelope())).isEqualTo(l.get(1));
  }

  @Test
  void systemShutdownBateByteAByteELevantaOSinal() throws IOException {
    final List<String> l = linhas("system.shutdown.ok.jsonl");
    assertThat(encerrar).isFalse();
    final Dispatcher.Outcome fora = despachar(l.get(0));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    assertThat(emitido(((Dispatcher.Outcome.Respond) fora).envelope())).isEqualTo(l.get(1));
    assertThat(encerrar).isTrue();
  }

  @Test
  void metodoDesconhecidoBateByteAByte() throws IOException {
    final String esperado = linhas("protocol.unknown-method.jsonl").get(0);
    final JsonNode golden = inbound.readTree(esperado);
    final String id = golden.get("id").asText();
    final String metodo = golden.get("error").get("params").get("method").asText();

    final String requisicao =
        "{\"id\":\""
            + id
            + "\",\"kind\":\"request\",\"method\":\""
            + metodo
            + "\",\"payload\":{},\"protocol\":1}";
    final Dispatcher.Outcome fora = despachar(requisicao);
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    assertThat(emitido(((Dispatcher.Outcome.Respond) fora).envelope())).isEqualTo(esperado);
  }

  @Test
  void quadroComVersaoDiferenteEDescartadoEmSilencio() throws IOException {
    final List<String> l = linhas("protocol.discarded-frame.jsonl");
    assertThat(l).hasSize(1);
    final Dispatcher.Outcome fora = despachar(l.get(0));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Discard.class);
    assertThat(((Dispatcher.Outcome.Discard) fora).reason())
        .isEqualTo(Dispatcher.DiscardReason.PROTOCOL_MISMATCH);
  }

  @Test
  void todaFixtureOuEDirigidaOuEDeclaradaNaoDirigivel() throws IOException {
    final Set<String> uniao = new TreeSet<>(DIRIGIDOS);
    uniao.addAll(EXPECTADOS_NAO_DIRIGIVEIS.keySet());
    assertThat(uniao)
        .as("acrescentar uma fixture obriga a decidir de que lado ela cai")
        .isEqualTo(arquivos());
  }

  @Test
  void nenhumaFixtureNaoDirigidaTemHandlerRegistrado() {
    final List<String> falhas = new ArrayList<>();
    for (final String arquivo : EXPECTADOS_NAO_DIRIGIVEIS.keySet()) {
      final String haste = arquivo.substring(0, arquivo.length() - ".jsonl".length());
      for (final String metodo : dispatcher.registeredMethods()) {
        if (haste.equals(metodo) || haste.startsWith(metodo + ".")) {
          falhas.add(arquivo + " é pulada mas " + metodo + " está implementado");
        }
      }
    }
    assertThat(falhas)
        .as("uma fixture não pode ser pulada por um método já implementado")
        .isEmpty();
  }

  @Test
  void oDespachanteRegistraExatamenteOsDoisMetodosDaFase() {
    assertThat(dispatcher.registeredMethods())
        .containsExactlyInAnyOrder("system.ping", "system.shutdown");
  }

  @Test
  void asConstantesDeProtocoloBatemComOsGoldens() throws IOException {
    assertThat(AnalysisConstants.PROTOCOL_VERSION).isEqualTo(1);
    final JsonNode ready = inbound.readTree(linhas("protocol.ready.mvp.jsonl").get(0));
    assertThat(ready.get("protocol").asInt()).isEqualTo(AnalysisConstants.PROTOCOL_VERSION);
    assertThat(ready.get("payload").get("capabilities")).isEmpty();
  }
}
