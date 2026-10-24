package br.pucpr.tcc.strata.analyzer.protocol.method;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.GUIDED_TOUR;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.ReadableAnalysis;
import br.pucpr.tcc.strata.analyzer.analysis.RegistrySettings;
import br.pucpr.tcc.strata.analyzer.analysis.StageExecutor;
import br.pucpr.tcc.strata.analyzer.analysis.StageOutcome;
import br.pucpr.tcc.strata.analyzer.analysis.StagePlan;
import br.pucpr.tcc.strata.analyzer.analysis.Stages;
import br.pucpr.tcc.strata.analyzer.analysis.StructuralGraphStage;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.structure.ElementNotFoundException;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class StructureReadsTest {

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final String FIXTURE_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

  private static final String DESCONHECIDA = "an_01JQ8Z9K3M4N5P6Q7R8S9T0VZZ";

  private static final Duration AWAIT = Duration.ofSeconds(60);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final ProtocolError ELEMENT_NOT_FOUND =
      new ProtocolError(
          "ELEMENT_NOT_FOUND",
          "o elemento não é um elemento interno desta análise",
          ErrorCodes.ORIGIN_ANALYZER,
          Map.of());

  private static final String E_AGUDO = Character.toString(0x00E9);

  private static final List<String> PACOTES =
      List.of(
          "pkg:br.pucpr.legacy.api",
          "pkg:br.pucpr.legacy.core",
          "pkg:br.pucpr.legacy.core.gen",
          "pkg:br.pucpr.legacy.core.spi");

  @TempDir Path dados;
  @TempDir Path repositorios;

  private final ObjectMapper inbound = JsonLines.strictInboundMapper();
  private final Quadros quadros = new Quadros();
  private final List<CountDownLatch> trincos = new ArrayList<>();
  private final AtomicInteger pedidos = new AtomicInteger();

  private AnalysisRegistry registro;
  private Dispatcher dispatcher;

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void fechar() {
    trincos.forEach(CountDownLatch::countDown);
    if (registro != null) {
      registro.close();
    }
  }

  private static final class Quadros implements FrameSink {
    private final List<JsonNode> recebidos = new ArrayList<>();
    private final CanonicalJson canonical = new CanonicalJson();

    @Override
    public synchronized void emit(Envelope envelope) throws IOException {
      recebidos.add(JSON.readTree(canonical.toBytes(envelope)));
      notifyAll();
    }

    synchronized JsonNode esperar(Predicate<JsonNode> quero, String oQue)
        throws InterruptedException {
      final long prazo = System.nanoTime() + AWAIT.toNanos();
      while (true) {
        for (final JsonNode quadro : recebidos) {
          if (quero.test(quadro)) {
            return quadro;
          }
        }
        final long falta = prazo - System.nanoTime();
        if (falta <= 0) {
          throw new AssertionError("esperei por " + oQue + "; chegaram " + recebidos);
        }
        wait(Math.max(1, falta / 1_000_000));
      }
    }

    String terminal(String analysisId) throws InterruptedException {
      return esperar(
              q ->
                  analysisId.equals(q.path("payload").path("analysisId").asText())
                      && Arrays.asList(
                              "analysis.completed", "analysis.cancelled", "analysis.failed")
                          .contains(q.path("event").asText()),
              "o evento terminal de " + analysisId)
          .path("event")
          .asText();
    }
  }

  private static AnalysisId idNumero(int n) {
    final int sufixo = 60 + n;
    return new AnalysisId(
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V"
            + CROCKFORD.charAt(sufixo / 32 % 32)
            + CROCKFORD.charAt(sufixo % 32));
  }

  private static StagePlan planoComGrafo() {
    return Stages.production()
        .with(STRUCTURAL_GRAPH, new StructuralGraphStage(ExternalPackages.load()));
  }

  private StageExecutor quePrende(CountDownLatch dentro) {
    final CountDownLatch solta = new CountDownLatch(1);
    trincos.add(solta);
    return context -> {
      dentro.countDown();
      final long prazo = System.nanoTime() + AWAIT.toNanos();
      while (System.nanoTime() < prazo) {
        context.cancellation().throwIfCancelled();
        try {
          if (solta.await(10, TimeUnit.MILLISECONDS)) {
            break;
          }
        } catch (InterruptedException interrompido) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrompido);
        }
      }
      return StageOutcome.skipped();
    };
  }

  private void montar(StagePlan plano) throws IOException {
    final AtomicInteger proximo = new AtomicInteger();
    registro =
        new AnalysisRegistry(
            RegistrySettings.production(PersistenceRoot.prepare(dados), quadros, "0.0.0-test")
                .withClock(InstantSource.fixed(TestRepositories.FIXTURE_IDENT.getWhenAsInstant()))
                .withNanoClock(() -> 0L)
                .withIds(() -> idNumero(proximo.getAndIncrement()))
                .withPlan(plano));
    dispatcher = Dispatcher.of(inbound, Handlers.all(() -> 0L, new AtomicBoolean(), registro));
  }

  private Path multiModulo() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("multi-module");
    TestRepositories.reproduceCorpusFixture("multi-module", repositorio);
    return repositorio;
  }

  private String novoId() {
    return "req_01JQ8ZH" + CROCKFORD.charAt(pedidos.incrementAndGet() % 32) + "B2C3D4E5F6G7H8J9K0";
  }

  private Dispatcher.Outcome.Respond despacharQuadro(String quadro) throws IOException {
    final Dispatcher.Outcome fora = dispatcher.dispatch(dispatcher.parse(quadro.getBytes(UTF_8)));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return (Dispatcher.Outcome.Respond) fora;
  }

  private String despachar(String id, String metodo, JsonNode payload) throws IOException {
    final ObjectNode quadro = inbound.createObjectNode();
    quadro.put("id", id).put("kind", "request").put("method", metodo);
    quadro.set("payload", payload);
    quadro.put("protocol", 1);
    return bytes(despacharQuadro(JSON.writeValueAsString(quadro)).envelope());
  }

  private static ObjectNode comAnalise(String analysisId, String payload) throws IOException {
    final ObjectNode corpo = JSON.createObjectNode().put("analysisId", analysisId);
    corpo.setAll((ObjectNode) JSON.readTree(payload));
    return corpo;
  }

  private static String bytes(Envelope envelope) {
    return new String(new CanonicalJson().toBytes(envelope), UTF_8);
  }

  private static String erro(String id, ProtocolError error) {
    return bytes(Envelope.error(id, error));
  }

  private static String ok(String id, String payload) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\"response\",\"ok\":true,\"payload\":"
        + payload
        + ",\"protocol\":1}";
  }

  private String comecar(Path repositorio) throws IOException {
    final ObjectNode quadro = inbound.createObjectNode();
    quadro.put("id", novoId()).put("kind", "request").put("method", "analysis.start");
    quadro.set("payload", JSON.createObjectNode());
    quadro.put("protocol", 1).put("repositoryPath", repositorio.toString());
    final Dispatcher.Outcome.Respond resposta = despacharQuadro(JSON.writeValueAsString(quadro));
    final JsonNode lida = JSON.readTree(bytes(resposta.envelope()));
    assertThat(lida.path("ok").asBoolean()).as(lida.toString()).isTrue();
    resposta.afterEmit().run();
    return lida.path("payload").path("analysisId").asText();
  }

  private String pronta() throws Exception {
    montar(planoComGrafo());
    final String id = comecar(multiModulo());
    assertThat(quadros.terminal(id)).isEqualTo("analysis.completed");
    assertThat(id).isEqualTo(FIXTURE_ID);
    return id;
  }

  private static List<String[]> tresLeituras() {
    return List.of(
        new String[] {"graph.slice", "{\"level\":\"PACKAGE\"}"},
        new String[] {"outline.children", "{\"parentId\":null}"},
        new String[] {"graph.search", "{\"query\":\"c\"}"});
  }

  private void verificarQueNenhumaLeituraServe(String analysisId, String porque)
      throws IOException {
    for (final String[] leitura : tresLeituras()) {
      final String id = novoId();
      assertThat(despachar(id, leitura[0], comAnalise(analysisId, leitura[1])))
          .as(leitura[0] + ": " + porque)
          .isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {"edgeTypes|[\"CALLS\"]", "minResolution|\"RESOLVED\""})
  void edgeTypesEMinResolutionSaoPropriedadesDesconhecidas(String chave, String valor)
      throws Exception {
    montar(planoComGrafo());
    final String id = novoId();
    final ObjectNode payload = comAnalise(DESCONHECIDA, "{\"level\":\"PACKAGE\"}");
    payload.set(chave, JSON.readTree(valor));

    assertThat(despachar(id, "graph.slice", payload))
        .isEqualTo(
            erro(
                id,
                ErrorCodes.invalidPayload(
                    "graph.slice", "propriedade desconhecida \"" + chave + "\"")));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "graph.slice|{}|level é obrigatório",
        "graph.slice|{\"level\":null}|level é obrigatório",
        "graph.slice|{\"level\":\"TYPE_NEIGHBORHOOD\"}|o nível TYPE_NEIGHBORHOOD não é atendido",
        "graph.slice|{\"level\":\"TYPE_NEIGHBORHOOD\",\"parentId\":\"type:br.pucpr.legacy.core.Kind\"}"
            + "|o nível TYPE_NEIGHBORHOOD não é atendido",
        "graph.slice|{\"level\":\"PACKAGE\",\"parentId\":\"pkg:br.pucpr.legacy.core\"}"
            + "|parentId deve estar ausente ou ser null no nível PACKAGE",
        "graph.slice|{\"level\":\"PACKAGE\",\"parentId\":\"pkg:\"}"
            + "|parentId deve estar ausente ou ser null no nível PACKAGE",
        "graph.slice|{\"level\":\"TYPE\"}|parentId deve ser um id de pacote no nível TYPE",
        "graph.slice|{\"level\":\"TYPE\",\"parentId\":null}"
            + "|parentId deve ser um id de pacote no nível TYPE",
        "graph.slice|{\"level\":\"TYPE\",\"parentId\":\"type:br.pucpr.legacy.core.Catalog\"}"
            + "|parentId deve ser um id de pacote no nível TYPE",
        "graph.slice|{\"level\":\"TYPE\",\"parentId\":\"method:br.pucpr.legacy.core.Catalog#find()\"}"
            + "|parentId deve ser um id de pacote no nível TYPE",
        "graph.slice|{\"level\":\"PACKAGE\",\"limit\":0}|limit deve ser pelo menos 1",
        "graph.slice|{\"level\":\"TYPE\",\"parentId\":\"pkg:br.pucpr.legacy.core\",\"limit\":-1}"
            + "|limit deve ser pelo menos 1",
        "outline.children|{\"parentId\":null,\"limit\":0}|limit deve ser pelo menos 1",
        "outline.children|{\"parentId\":\"method:br.pucpr.legacy.core.Catalog#find()\"}"
            + "|parentId deve ser null, um id de pacote ou um id de tipo",
        "outline.children|{\"parentId\":\"file:core/src/main/java/br/pucpr/legacy/core/Kind.java\"}"
            + "|parentId deve ser null, um id de pacote ou um id de tipo",
        "outline.children|{\"parentId\":null,\"cursor\":\"\"}|o cursor está malformado",
        "outline.children|{\"parentId\":null,\"cursor\":\"!!!!!!!!!!!!\"}|o cursor está malformado",
        "outline.children|{\"parentId\":null,\"cursor\":\"AQAAAAAAAAAB=\"}|o cursor está malformado",
        "outline.children|{\"parentId\":null,\"cursor\":\"AgAAAAAAAAAB\"}|o cursor está malformado",
        "outline.children|{\"parentId\":null,\"cursor\":\"AQAAAAAAAAA\"}|o cursor está malformado",
        "graph.search|{}|query é obrigatório",
        "graph.search|{\"query\":null}|query é obrigatório",
        "graph.search|{\"query\":\"c\",\"limit\":0}|limit deve ser pelo menos 1",
        "graph.search|{\"query\":\"c\",\"kinds\":[\"TYPE\",null]}|kinds não pode conter null"
      })
  void cadaRecusaPuraTemAFraseFixaEVemAntesDaProntidao(String metodo, String payload, String detail)
      throws Exception {
    montar(planoComGrafo());
    final String id = novoId();

    assertThat(despachar(id, metodo, comAnalise(DESCONHECIDA, payload)))
        .isEqualTo(erro(id, ErrorCodes.invalidPayload(metodo, detail)));
  }

  @Test
  void umCursorAcimaDoTetoEMalformado() throws Exception {
    montar(planoComGrafo());
    final String id = novoId();
    final ObjectNode payload = comAnalise(DESCONHECIDA, "{\"parentId\":null}");
    payload.put("cursor", "A".repeat(AnalysisConstants.MAX_CURSOR_CHARS + 1));

    assertThat(despachar(id, "outline.children", payload))
        .isEqualTo(
            erro(id, ErrorCodes.invalidPayload("outline.children", "o cursor está malformado")));
  }

  @Test
  void aConsultaAcimaDe128PontosDeCodigoEARecusaUnicaEContaPontosDeCodigo() throws Exception {
    montar(planoComGrafo());
    final String emoji = Character.toString(0x1F600);
    final String demais = "query passa de 128 code points";

    for (final String consulta : List.of("a".repeat(129), emoji.repeat(129))) {
      final String id = novoId();
      final ObjectNode payload = comAnalise(DESCONHECIDA, "{}").put("query", consulta);
      assertThat(despachar(id, "graph.search", payload))
          .as(consulta.length() + " unidades UTF-16")
          .isEqualTo(erro(id, ErrorCodes.invalidPayload("graph.search", demais)));
    }
    final String id = novoId();
    final ObjectNode cabe = comAnalise(DESCONHECIDA, "{}").put("query", emoji.repeat(128));
    assertThat(despachar(id, "graph.search", cabe))
        .as("128 pontos de código, 256 unidades UTF-16: passa do teto e para na prontidão")
        .isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
  }

  @Test
  void oTetoDoIdEEmBytesVemAntesDoParseENaoEcoa() throws Exception {
    montar(planoComGrafo());
    final int teto = AnalysisConstants.MAX_ELEMENT_ID_BYTES;
    final String ascii = "pkg:" + "a".repeat(teto - 3);
    final String acentuado = "pkg:" + E_AGUDO.repeat((teto - 2) / 2);
    assertThat(ascii.getBytes(UTF_8)).hasSize(teto + 1);
    assertThat(acentuado.getBytes(UTF_8)).hasSize(teto + 2);
    assertThat(acentuado.length()).isLessThan(teto);

    final List<String> frases = new ArrayList<>();
    for (final String pai : List.of(ascii, acentuado)) {
      frases.add(recusaDoPai("graph.slice", "{\"level\":\"TYPE\"}", pai));
      frases.add(recusaDoPai("outline.children", "{}", pai));
    }
    assertThat(Set.copyOf(frases))
        .as("uma frase fixa, a mesma para todo id acima do teto e nos dois métodos")
        .hasSize(1);

    final String gramatica = recusaDoPai("graph.slice", "{\"level\":\"TYPE\"}", "pfx:br.pucpr");
    assertThat(gramatica).doesNotContain("pfx:");

    for (final String noTeto :
        List.of("pkg:" + "a".repeat(teto - 4), "pkg:" + E_AGUDO.repeat((teto - 4) / 2))) {
      assertThat(noTeto.getBytes(UTF_8)).hasSize(teto);
      final String id = novoId();
      final ObjectNode payload = comAnalise(DESCONHECIDA, "{\"level\":\"TYPE\"}");
      payload.put("parentId", noTeto);
      assertThat(despachar(id, "graph.slice", payload))
          .as("no teto o id é aceito e o pedido para na prontidão")
          .isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
    }
  }

  private String recusaDoPai(String metodo, String payload, String pai) throws IOException {
    final ObjectNode corpo = comAnalise(DESCONHECIDA, payload);
    corpo.put("parentId", pai);
    final JsonNode resposta = JSON.readTree(despachar(novoId(), metodo, corpo));
    assertThat(resposta.path("ok").asBoolean()).as(resposta.toString()).isFalse();
    assertThat(resposta.path("error").path("code").asText())
        .as(metodo)
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(resposta.path("error").path("params"))
        .isEqualTo(JSON.createObjectNode().put("method", metodo));
    final String detail = resposta.path("error").path("detail").asText();
    assertThat(detail)
        .as("a frase nunca ecoa o id")
        .doesNotContain("aaaa")
        .doesNotContain(E_AGUDO + E_AGUDO);
    return detail;
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "graph.slice|{\"level\":\"PACKAGE\"}",
        "graph.slice|{\"level\":\"TYPE\",\"parentId\":\"pkg:br.pucpr.legacy.core\"}",
        "outline.children|{\"parentId\":null}",
        "outline.children|{\"parentId\":\"pkg:br.pucpr.legacy.core\"}",
        "graph.search|{\"query\":\"c\"}",
        "graph.search|{\"query\":\"\"}",
        "graph.search|{\"query\":\"c\",\"kinds\":[\"METHOD\"]}"
      })
  void umaAnaliseDesconhecidaEAnalysisNotFoundEmTodaLeitura(String metodo, String payload)
      throws Exception {
    montar(planoComGrafo());
    final String id = novoId();

    assertThat(despachar(id, metodo, comAnalise(DESCONHECIDA, payload)))
        .isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
  }

  @Test
  void umaAnaliseRodandoAntesDeOGrafoCompletarNaoEServida() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoComGrafo().with(STRUCTURAL_GRAPH, quePrende(dentro)));
    final String id = comecar(multiModulo());
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    assertThat(registro.readable(new AnalysisId(id)))
        .as("anti-vacuidade: a análise é legível; quem recusa é o estado do grafo")
        .isPresent();

    verificarQueNenhumaLeituraServe(id, "o STRUCTURAL_GRAPH ainda roda");
    final String vazia = novoId();
    assertThat(despachar(vazia, "graph.search", comAnalise(id, "{\"query\":\"\"}")))
        .isEqualTo(erro(vazia, ErrorCodes.analysisNotFound()));
    assertThatThrownBy(() -> StructureReads.ready(registro, new AnalysisId(id)))
        .isInstanceOfSatisfying(
            RequestRejected.class,
            recusa -> assertThat(recusa.error()).isEqualTo(ErrorCodes.analysisNotFound()));
  }

  @Test
  void rodandoDepoisDoGrafoEServidaECanceladaDeixaDeSer() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoComGrafo().with(GUIDED_TOUR, quePrende(dentro)));
    final String id = comecar(multiModulo());
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    final JsonNode fatia =
        JSON.readTree(
            despachar(novoId(), "graph.slice", comAnalise(id, "{\"level\":\"PACKAGE\"}")));
    assertThat(fatia.path("ok").asBoolean()).as(fatia.toString()).isTrue();
    final List<String> nos = new ArrayList<>();
    fatia.path("payload").path("nodes").forEach(no -> nos.add(no.path("id").asText()));
    assertThat(nos).containsExactlyElementsOf(PACOTES);
    final JsonNode raiz =
        JSON.readTree(
            despachar(novoId(), "outline.children", comAnalise(id, "{\"parentId\":null}")));
    assertThat(raiz.path("ok").asBoolean()).as(raiz.toString()).isTrue();
    assertThat(raiz.path("payload").path("items")).hasSize(PACOTES.size());
    final JsonNode busca =
        JSON.readTree(despachar(novoId(), "graph.search", comAnalise(id, "{\"query\":\"c\"}")));
    assertThat(busca.path("ok").asBoolean()).as(busca.toString()).isTrue();
    assertThat(busca.path("payload").path("results")).hasSize(5);

    final ObjectNode cancelar = inbound.createObjectNode();
    cancelar.put("id", novoId()).put("kind", "request").put("method", "analysis.cancel");
    cancelar.set("payload", JSON.createObjectNode().put("analysisId", id));
    cancelar.put("protocol", 1);
    despacharQuadro(JSON.writeValueAsString(cancelar)).afterEmit().run();
    assertThat(quadros.terminal(id)).isEqualTo("analysis.cancelled");

    verificarQueNenhumaLeituraServe(id, "uma análise CANCELLED não é legível");
  }

  @Test
  void numaAnaliseProntaOQueNadaPodeAcharRespondeVazio() throws Exception {
    final String analise = pronta();
    final String vazio = "{\"results\":[]}";

    for (final String consulta :
        List.of("", Character.toString(0x0301), Character.toString(0x200B))) {
      final String id = novoId();
      assertThat(despachar(id, "graph.search", comAnalise(analise, "{}").put("query", consulta)))
          .as("a dobra de %s é vazia", consulta.codePoints().boxed().toList())
          .isEqualTo(ok(id, vazio));
    }
    final String solitario = novoId();
    final String quadro =
        "{\"id\":\""
            + solitario
            + "\",\"kind\":\"request\",\"method\":\"graph.search\",\"payload\":{\"analysisId\":\""
            + analise
            + "\",\"query\":\"\\ud800\"},\"protocol\":1}";
    assertThat(bytes(despacharQuadro(quadro).envelope()))
        .as("um substituto solitário")
        .isEqualTo(ok(solitario, vazio));
    for (final String tipos : List.of("[\"METHOD\"]", "[\"METHOD\",\"FILE\"]")) {
      final String id = novoId();
      assertThat(
              despachar(
                  id,
                  "graph.search",
                  comAnalise(analise, "{\"query\":\"c\",\"kinds\":" + tipos + "}")))
          .as("kinds " + tipos)
          .isEqualTo(ok(id, vazio));
    }
  }

  @Test
  void umPaiQueNaoEElementoInternoEElementNotFound() throws Exception {
    final String analise = pronta();

    for (final String pai :
        List.of(
            "pkg:br.pucpr.legacy.nowhere",
            "type:br.pucpr.legacy.core.Nowhere",
            "type:java.lang.String")) {
      final String id = novoId();
      assertThat(despachar(id, "outline.children", comAnalise(analise, "{}").put("parentId", pai)))
          .as("outline.children de " + pai)
          .isEqualTo(erro(id, ELEMENT_NOT_FOUND));
    }
    for (final String pai : List.of("pkg:br.pucpr.legacy.nowhere", "pkg:java.lang")) {
      final String id = novoId();
      assertThat(
              despachar(
                  id,
                  "graph.slice",
                  comAnalise(analise, "{\"level\":\"TYPE\"}").put("parentId", pai)))
          .as("graph.slice TYPE de " + pai)
          .isEqualTo(erro(id, ELEMENT_NOT_FOUND));
    }
    final String folha = novoId();
    assertThat(
            despachar(
                folha,
                "outline.children",
                comAnalise(analise, "{\"parentId\":\"type:br.pucpr.legacy.core.Catalog\"}")))
        .isEqualTo(ok(folha, "{\"items\":[],\"nextCursor\":null}"));
  }

  @Test
  void aGuardaDeRetencaoEOPrimeiroComandoDaUnidade() throws Exception {
    final String analise = pronta();
    final ReadableAnalysis legivel = registro.readable(new AnalysisId(analise)).orElseThrow();
    legivel
        .connections()
        .write(
            conexao -> {
              try (PreparedStatement muda =
                  conexao.prepareStatement(
                      "UPDATE analysis SET status = 'CANCELLED' WHERE analysis_id = ?")) {
                muda.setString(1, analise);
                assertThat(muda.executeUpdate()).isOne();
              }
              return null;
            });
    assertThat(registro.readable(new AnalysisId(analise)))
        .as("anti-vacuidade: o registro ainda a entrega")
        .isPresent();

    verificarQueNenhumaLeituraServe(analise, "a linha analysis não é mais RUNNING nem COMPLETED");
  }

  @Test
  void umCacheFechadoEmbaixoDaLeituraEAnalysisNotFound() throws Exception {
    final String analise = pronta();
    final SqliteConnections conexoes =
        registro.readable(new AnalysisId(analise)).orElseThrow().connections();
    final CountDownLatch presas = new CountDownLatch(AnalysisConstants.SQLITE_READER_CONNECTIONS);
    final CountDownLatch soltar = new CountDownLatch(1);
    trincos.add(soltar);
    final List<Thread> leitoras = new ArrayList<>();
    for (int i = 0; i < AnalysisConstants.SQLITE_READER_CONNECTIONS; i++) {
      leitoras.add(
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    try {
                      conexoes.read(conexao -> esperarPresa(presas, soltar));
                    } catch (SQLException falha) {
                      throw new IllegalStateException(falha);
                    }
                  }));
    }
    assertThat(presas.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    final String id = novoId();
    final AtomicReference<String> resposta = new AtomicReference<>();
    final Thread pedido =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try {
                    resposta.set(
                        despachar(
                            id, "graph.slice", comAnalise(analise, "{\"level\":\"PACKAGE\"}")));
                  } catch (IOException falha) {
                    throw new IllegalStateException(falha);
                  }
                });
    assertThat(ate(() -> !pedido.isAlive() || paradaEm(pedido, "read")))
        .as("o pedido chegou ao semáforo do read, ou respondeu antes")
        .isTrue();
    if (pedido.isAlive()) {
      final Thread fechamento = Thread.ofPlatform().daemon().start(registro::close);
      assertThat(ate(() -> paradaEm(fechamento, "close")))
          .as("o close() marcou as conexões e espera as licenças")
          .isTrue();
      soltar.countDown();
      fechamento.join(AWAIT.toMillis());
      assertThat(fechamento.isAlive()).isFalse();
    }
    soltar.countDown();
    pedido.join(AWAIT.toMillis());
    for (final Thread leitora : leitoras) {
      leitora.join(AWAIT.toMillis());
    }

    assertThat(resposta.get()).isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
  }

  private static boolean esperarPresa(CountDownLatch presas, CountDownLatch soltar) {
    presas.countDown();
    try {
      return soltar.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrompida) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrompida);
    }
  }

  private static boolean paradaEm(Thread thread, String metodo) {
    if (thread.getState() != Thread.State.WAITING) {
      return false;
    }
    for (final StackTraceElement quadro : thread.getStackTrace()) {
      if (SqliteConnections.class.getName().equals(quadro.getClassName())
          && metodo.equals(quadro.getMethodName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean ate(BooleanSupplier condicao) throws InterruptedException {
    final CountDownLatch nunca = new CountDownLatch(1);
    final long prazo = System.nanoTime() + AWAIT.toNanos();
    while (!condicao.getAsBoolean()) {
      if (System.nanoTime() > prazo) {
        return false;
      }
      assertThat(nunca.await(5, TimeUnit.MILLISECONDS)).isFalse();
    }
    return true;
  }

  @Test
  void aPortaComumTraduzCadaDesfechoDaUnidade() throws Exception {
    final AnalysisId analise = new AnalysisId(pronta());
    final long pk = registro.readable(analise).orElseThrow().analysisPk();

    assertThat(StructureReads.ready(registro, analise).analysisPk()).isEqualTo(pk);
    final Long lida =
        StructureReads.read(registro, "graph.slice", analise, (conexao, chave) -> chave);
    assertThat(lida)
        .as("a consulta recebe a chave da análise e devolve o que materializou")
        .isEqualTo(pk);
    assertThat(
            recusa(
                () ->
                    StructureReads.read(
                        registro,
                        "graph.slice",
                        analise,
                        (conexao, chave) -> {
                          throw new SQLException("/Users/someone/secret/analysis.sqlite");
                        })))
        .isEqualTo(ErrorCodes.internalUnexpected("java.sql.SQLException"));
    final SqliteConnections conexoes = registro.readable(analise).orElseThrow().connections();
    assertThat(
            recusa(
                () ->
                    StructureReads.read(
                        registro,
                        "graph.slice",
                        analise,
                        (conexao, chave) -> conexoes.read(aninhada -> 1))))
        .as("o aninhamento é defeito: o cache continua aberto, e a análise, legível")
        .isEqualTo(ErrorCodes.internalUnexpected("java.lang.IllegalStateException"));
    assertThat(
            recusa(
                () ->
                    StructureReads.read(
                        registro,
                        "outline.children",
                        analise,
                        (conexao, chave) -> {
                          throw new ElementNotFoundException();
                        })))
        .isEqualTo(ELEMENT_NOT_FOUND);
    assertThat(
            recusa(
                () ->
                    StructureReads.read(
                        registro,
                        "outline.children",
                        analise,
                        (conexao, chave) -> {
                          throw new InvalidStructureRequest(
                              InvalidStructureRequest.Reason.CURSOR_UNKNOWN);
                        })))
        .isEqualTo(
            ErrorCodes.invalidPayload(
                "outline.children", "o cursor não aponta para uma linha desta listagem"));
  }

  private static ProtocolError recusa(Runnable chamada) {
    try {
      chamada.run();
    } catch (RequestRejected recusada) {
      return recusada.error();
    }
    throw new AssertionError("a chamada devia ter sido recusada com RequestRejected");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"graph.slice", "outline.children"})
  void asConferenciasPurasDaPorta(String metodo) {
    final int teto = AnalysisConstants.MAX_ELEMENT_ID_BYTES;
    assertThat(StructureReads.elementId(metodo, null)).isNull();
    final String noTeto = "pkg:" + "a".repeat(teto - 4);
    assertThat(StructureReads.elementId(metodo, noTeto)).isEqualTo(new CodeElementId(noTeto));
    assertThat(StructureReads.elementId(metodo, "pkg:")).isEqualTo(new CodeElementId("pkg:"));
    for (final String recusado : List.of(noTeto + "a", "pfx:br.pucpr", "nope")) {
      final ProtocolError erro = recusa(() -> StructureReads.elementId(metodo, recusado));
      assertThat(erro.code()).as(recusado).isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
      assertThat(erro.params()).isEqualTo(Map.of("method", metodo));
      assertThat(erro.detail()).doesNotContain(recusado);
    }

    assertThat(
            StructureReads.invalid(
                    metodo,
                    new InvalidStructureRequest(InvalidStructureRequest.Reason.LIMIT_NOT_POSITIVE))
                .error())
        .isEqualTo(ErrorCodes.invalidPayload(metodo, "limit deve ser pelo menos 1"));
  }
}
