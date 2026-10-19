package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidationReason;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.io.TempDir;

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
          "cache.invalidated.corrupt.jsonl",
          "cache.invalidated.head-changed.jsonl",
          "cache.invalidated.schema-version.jsonl",
          "protocol.ready.mvp.jsonl",
          "protocol.discarded-frame.jsonl",
          "protocol.unknown-method.jsonl",
          "system.ping.ok.jsonl",
          "system.shutdown.ok.jsonl",
          "repository.describe.ok.jsonl",
          "repository.describe.bare.jsonl",
          "repository.describe.no-java.jsonl");

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
    m.put("graph.search.hit.jsonl", "ainda não implementado");
    m.put("graph.slice.package.jsonl", "ainda não implementado");
    m.put("graph.slice.type.jsonl", "ainda não implementado");
    m.put("outline.children.orders.jsonl", "ainda não implementado");
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
  private final Dispatcher dispatcher = Dispatcher.of(inbound, Handlers.all(() -> 0L, encerrar));
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
    assertThat(arquivos()).contains("protocol.ready.mvp.jsonl").hasSize(25);
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

  private String despacharEmRepositorio(String arquivo, Path repositorio) throws IOException {
    final List<String> l = linhas(arquivo);
    assertThat(l).as(arquivo + ": requisição e resposta").hasSize(2);
    final ObjectNode requisicao = (ObjectNode) inbound.readTree(l.get(0));
    assertThat(requisicao.has("repositoryPath"))
        .as("a fixture não pode trazer o caminho absoluto da máquina de quem a escreveu")
        .isFalse();
    requisicao.put("repositoryPath", repositorio.toRealPath().toString());
    final Dispatcher.Outcome fora =
        dispatcher.dispatch(
            dispatcher.parse(
                inbound.writeValueAsString(requisicao).getBytes(StandardCharsets.UTF_8)));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return emitido(((Dispatcher.Outcome.Respond) fora).envelope());
  }

  @Test
  void repositoryDescribeOkBateByteAByte(@TempDir Path temp) throws Exception {
    final Path repositorio = temp.toRealPath().resolve("multi-module");
    final var commit = TestRepositories.reproduceCorpusFixture("multi-module", repositorio);
    assertThat(commit.name())
        .as("a reprodução tem de dar o id que o make-fixtures.sh gravou; arquivo solto no corpus?")
        .isEqualTo(TestRepositories.corpusCommitId("multi-module"));

    assertThat(despacharEmRepositorio("repository.describe.ok.jsonl", repositorio))
        .isEqualTo(linhas("repository.describe.ok.jsonl").get(1));
  }

  @Test
  void repositoryDescribeBareBateByteAByte(@TempDir Path temp) throws Exception {
    final Path bare = TestRepositories.bare(temp.toRealPath().resolve("bare.git"));

    assertThat(despacharEmRepositorio("repository.describe.bare.jsonl", bare))
        .isEqualTo(linhas("repository.describe.bare.jsonl").get(1));
  }

  @Test
  void repositoryDescribeSemJavaBateByteAByte(@TempDir Path temp) throws Exception {
    final Path repositorio = temp.toRealPath().resolve("sem-java");
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(repositorio, "README.md", "# sem java\n");
      TestRepositories.commitAll(git, "primeiro");
    }

    assertThat(despacharEmRepositorio("repository.describe.no-java.jsonl", repositorio))
        .isEqualTo(linhas("repository.describe.no-java.jsonl").get(1));
  }

  private static String unicaLinha(String arquivo) throws IOException {
    final List<String> l = linhas(arquivo);
    assertThat(l).as(arquivo + ": um evento, nenhuma requisição").hasSize(1);
    return l.get(0);
  }

  @Test
  void cacheInvalidatedCorruptBateByteAByte() throws IOException {
    final String esperado = unicaLinha("cache.invalidated.corrupt.jsonl");

    final String emitido = emitido(Envelope.cacheInvalidated(new CacheInvalidation.Corrupt()));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void cacheInvalidatedSchemaVersionBateByteAByte() throws IOException {
    final String esperado = unicaLinha("cache.invalidated.schema-version.jsonl");

    final String emitido =
        emitido(Envelope.cacheInvalidated(new CacheInvalidation.SchemaVersion(2, 1)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void cacheInvalidatedHeadChangedBateByteAByte() throws IOException {
    final String esperado = unicaLinha("cache.invalidated.head-changed.jsonl");

    final String emitido =
        emitido(
            Envelope.cacheInvalidated(
                new CacheInvalidation.Outdated(CacheInvalidationReason.HEAD_CHANGED)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void todoMotivoDePoliticaSaiComErroNulo() throws IOException {
    for (final CacheInvalidationReason motivo :
        List.of(
            CacheInvalidationReason.WORKTREE_DIRTY,
            CacheInvalidationReason.CONFIG_CHANGED,
            CacheInvalidationReason.HEAD_CHANGED)) {
      final JsonNode payload =
          inbound
              .readTree(emitido(Envelope.cacheInvalidated(new CacheInvalidation.Outdated(motivo))))
              .get("payload");

      assertThat(payload.get("reason").asText()).as(motivo.name()).isEqualTo(motivo.name());
      assertThat(payload.get("error").isNull()).as(motivo.name()).isTrue();
    }
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
  void oDespachanteRegistraExatamenteOsTresMetodosJaImplementados() {
    assertThat(dispatcher.registeredMethods())
        .containsExactlyInAnyOrder("system.ping", "system.shutdown", "repository.describe");
  }

  @Test
  void asConstantesDeProtocoloBatemComOsGoldens() throws IOException {
    assertThat(AnalysisConstants.PROTOCOL_VERSION).isEqualTo(1);
    final JsonNode ready = inbound.readTree(linhas("protocol.ready.mvp.jsonl").get(0));
    assertThat(ready.get("protocol").asInt()).isEqualTo(AnalysisConstants.PROTOCOL_VERSION);
    assertThat(ready.get("payload").get("capabilities")).isEmpty();
  }
}
