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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

  private static List<String[]> leiturasDoElemento() {
    return List.of(
        new String[] {"element.describe", "{\"elementId\":\"pkg:br.pucpr.legacy.core\"}"},
        new String[] {
          "element.dependencies",
          "{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.CatalogEntry\"}"
        },
        new String[] {
          "element.dependencyEvidence",
          "{\"source\":\"type:br.pucpr.legacy.core.Catalog\","
              + "\"target\":\"type:br.pucpr.legacy.core.spi.Store\"}"
        });
  }

  private static List<String[]> seisLeituras() {
    final List<String[]> seis = new ArrayList<>(tresLeituras());
    seis.addAll(leiturasDoElemento());
    return List.copyOf(seis);
  }

  private void verificarQueNenhumaLeituraServe(String analysisId, String porque)
      throws IOException {
    for (final String[] leitura : seisLeituras()) {
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
        "graph.search|{\"query\":\"c\",\"kinds\":[\"TYPE\",null]}|kinds não pode conter null",
        "element.describe|{\"elementId\":null}|elementId é obrigatório",
        "element.describe|{\"elementId\":\"pfx:br.pucpr\"}|elementId não é um id de elemento",
        "element.describe|{\"elementId\":\"nope\"}|elementId não é um id de elemento",
        "element.describe|{\"elementId\":\"method:br.pucpr.legacy.core.Catalog#add("
            + "br.pucpr.legacy.core.CatalogEntry)\"}|elementId deve ser um id de pacote ou de tipo",
        "element.describe|{\"elementId\":\"file:core/src/main/java/br/pucpr/legacy/core/Kind.java\"}"
            + "|elementId deve ser um id de pacote ou de tipo",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":null}|elementId é obrigatório",
        "element.dependencies|{\"direction\":null,\"elementId\":null}|elementId é obrigatório",
        "element.dependencies|{\"direction\":null,\"elementId\":\"type:br.pucpr.legacy.core.Kind\"}"
            + "|direction é obrigatório",
        "element.dependencies|{\"direction\":\"OUT\",\"elementId\":\"pfx:br.pucpr\"}"
            + "|elementId não é um id de elemento",
        "element.dependencies|{\"direction\":\"OUT\",\"elementId\":\"method:br.pucpr.legacy.core."
            + "Catalog#add(br.pucpr.legacy.core.CatalogEntry)\"}"
            + "|elementId deve ser um id de pacote ou de tipo",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"file:core/src/main/java/br/"
            + "pucpr/legacy/core/Kind.java\"}|elementId deve ser um id de pacote ou de tipo",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"method:a.B#m()\",\"limit\":0}"
            + "|elementId deve ser um id de pacote ou de tipo",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"limit\":0}|limit deve ser pelo menos 1",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"limit\":-1,\"cursor\":\"!\"}|limit deve ser pelo menos 1",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AQAAAAAAAAAq\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AQAAAAAAAAAAAQAAAAAAAAAB\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgIAAAAAAAAAAQAAAAAAAAAB\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAAAAAAAAAAAB\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAAAA\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAf__________\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAAA\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAAABA\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAA==\"}|o cursor está malformado",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAA+/\"}|o cursor está malformado",
        "outline.children|{\"parentId\":null,\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAAAB\"}"
            + "|o cursor está malformado",
        "element.dependencyEvidence|{\"source\":null,\"target\":\"type:a.B\"}|source é obrigatório",
        "element.dependencyEvidence|{\"source\":\"type:a.B\",\"target\":null}|target é obrigatório",
        "element.dependencyEvidence|{\"source\":null,\"target\":null}|source é obrigatório",
        "element.dependencyEvidence|{\"source\":\"pfx:a\",\"target\":\"type:a.B\"}"
            + "|source não é um id de elemento",
        "element.dependencyEvidence|{\"source\":\"type:a.B\",\"target\":\"nope\"}"
            + "|target não é um id de elemento",
        "element.dependencyEvidence|{\"source\":\"pkg:br.pucpr.legacy.core\","
            + "\"target\":\"type:br.pucpr.legacy.core.Kind\"}|source deve ser um id de tipo",
        "element.dependencyEvidence|{\"source\":\"method:a.B#m()\",\"target\":\"type:a.C\"}"
            + "|source deve ser um id de tipo",
        "element.dependencyEvidence|{\"source\":\"pkg:a\",\"target\":\"pkg:b\"}"
            + "|source deve ser um id de tipo",
        "element.dependencyEvidence|{\"source\":\"type:br.pucpr.legacy.core.Catalog\","
            + "\"target\":\"pkg:br.pucpr.legacy.core.spi\"}|target deve ser um id de tipo",
        "element.dependencyEvidence|{\"source\":\"type:a.B\",\"target\":\"file:a/B.java\"}"
            + "|target deve ser um id de tipo",
        "element.dependencyEvidence|{\"source\":\"type:a.B\",\"target\":\"type:a.B\"}"
            + "|source e target devem ser diferentes",
        "element.dependencyEvidence|{\"limit\":0,\"source\":\"type:a.B\",\"target\":\"type:a.B\"}"
            + "|source e target devem ser diferentes",
        "element.dependencyEvidence|{\"limit\":0,\"source\":\"type:a.B\",\"target\":\"type:a.C\"}"
            + "|limit deve ser pelo menos 1"
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
        "graph.search|{\"query\":\"c\",\"kinds\":[\"METHOD\"]}",
        "element.describe|{\"elementId\":\"pkg:br.pucpr.legacy.core\"}",
        "element.describe|{\"elementId\":\"type:br.pucpr.legacy.core.Catalog\"}",
        "element.describe|{\"elementId\":\"pkg:\"}",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\"}",
        "element.dependencies|{\"direction\":\"OUT\",\"elementId\":\"pkg:br.pucpr.legacy.core\","
            + "\"limit\":2000}",
        "element.dependencies|{\"direction\":\"OUT\",\"elementId\":\"pkg:br.pucpr.legacy.core\","
            + "\"limit\":2001}",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgAAAAAAAAAAAQAAAAAAAAAB\"}",
        "element.dependencies|{\"direction\":\"OUT\",\"elementId\":\"type:br.pucpr.legacy.core.Kind\","
            + "\"cursor\":\"AgEAAAAAAAAAAQAAAAAAAAAB\",\"limit\":1}",
        "element.dependencyEvidence|{\"source\":\"type:br.pucpr.legacy.core.Catalog\","
            + "\"target\":\"type:br.pucpr.legacy.core.spi.Store\"}",
        "element.dependencyEvidence|{\"limit\":51,\"source\":\"type:br.pucpr.legacy.api.Main\","
            + "\"target\":\"type:java.lang.String\"}"
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
  void asLeiturasDoElementoServemDepoisDoGrafoEnquantoAHistoriaRoda() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoComGrafo().with(GUIDED_TOUR, quePrende(dentro)));
    final String id = comecar(multiModulo());
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    for (final String[] leitura : leiturasDoElemento()) {
      final JsonNode resposta =
          JSON.readTree(despachar(novoId(), leitura[0], comAnalise(id, leitura[1])));
      assertThat(resposta.path("ok").asBoolean()).as(leitura[0] + ": " + resposta).isTrue();
    }

    final ObjectNode cancelar = inbound.createObjectNode();
    cancelar.put("id", novoId()).put("kind", "request").put("method", "analysis.cancel");
    cancelar.set("payload", JSON.createObjectNode().put("analysisId", id));
    cancelar.put("protocol", 1);
    despacharQuadro(JSON.writeValueAsString(cancelar)).afterEmit().run();
    assertThat(quadros.terminal(id)).isEqualTo("analysis.cancelled");

    verificarQueNenhumaLeituraServe(id, "uma análise CANCELLED não é legível");
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "element.dependencies|{\"direction\":\"SIDEWAYS\",\"elementId\":\"type:a.B\"}"
            + "|o payload não segue o schema",
        "element.dependencies|{\"direction\":\"in\",\"elementId\":\"type:a.B\"}"
            + "|o payload não segue o schema",
        "element.dependencies|{\"direction\":0,\"elementId\":\"type:a.B\"}"
            + "|o payload não segue o schema",
        "element.dependencies|{\"elementId\":\"type:a.B\"}|o payload não segue o schema",
        "element.dependencies|{\"direction\":\"IN\"}|o payload não segue o schema",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:a.B\",\"limit\":1.5}"
            + "|o payload não segue o schema",
        "element.describe|{}|o payload não segue o schema",
        "element.dependencyEvidence|{\"source\":\"type:a.B\"}|o payload não segue o schema",
        "element.dependencyEvidence|{\"target\":\"type:a.B\"}|o payload não segue o schema",
        "element.dependencyEvidence|{\"edgeId\":\"e_0123456789abcdef\",\"source\":\"type:a.B\","
            + "\"target\":\"type:a.C\"}|propriedade desconhecida \"edgeId\"",
        "element.describe|{\"elementId\":\"type:a.B\",\"simpleName\":\"B\"}"
            + "|propriedade desconhecida \"simpleName\"",
        "element.dependencies|{\"direction\":\"IN\",\"elementId\":\"type:a.B\",\"truncated\":true}"
            + "|propriedade desconhecida \"truncated\""
      })
  void osParamsDasLeiturasDoElementoSaoRecordsEstritos(String metodo, String payload, String detail)
      throws Exception {
    montar(planoComGrafo());
    final String id = novoId();

    assertThat(despachar(id, metodo, comAnalise(DESCONHECIDA, payload)))
        .isEqualTo(erro(id, ErrorCodes.invalidPayload(metodo, detail)));
  }

  @Test
  void oTetoDoIdNomeiaOCampoNasLeiturasDoElemento() throws Exception {
    montar(planoComGrafo());
    final int teto = AnalysisConstants.MAX_ELEMENT_ID_BYTES;
    final String ascii = "type:a." + "b".repeat(teto - 6);
    final String acentuado = "type:a." + E_AGUDO.repeat((teto - 5) / 2);
    final String noTeto = "type:a." + "b".repeat(teto - 7);
    assertThat(ascii.getBytes(UTF_8)).hasSize(teto + 1);
    assertThat(acentuado.getBytes(UTF_8)).hasSizeGreaterThan(teto);
    assertThat(acentuado.length()).isLessThan(teto);
    assertThat(noTeto.getBytes(UTF_8)).hasSize(teto);

    for (final String acima : List.of(ascii, acentuado)) {
      final Map<String, ObjectNode> pedidos = new LinkedHashMap<>();
      pedidos.put(
          "element.describe elementId", comAnalise(DESCONHECIDA, "{}").put("elementId", acima));
      pedidos.put(
          "element.dependencies elementId",
          comAnalise(DESCONHECIDA, "{\"direction\":\"OUT\"}").put("elementId", acima));
      pedidos.put(
          "element.dependencyEvidence source",
          comAnalise(DESCONHECIDA, "{\"target\":\"type:a.C\"}").put("source", acima));
      pedidos.put(
          "element.dependencyEvidence target",
          comAnalise(DESCONHECIDA, "{\"source\":\"type:a.C\"}").put("target", acima));
      for (final Map.Entry<String, ObjectNode> pedido : pedidos.entrySet()) {
        final String metodo = pedido.getKey().split(" ")[0];
        final String campo = pedido.getKey().split(" ")[1];
        final String id = novoId();
        final String resposta = despachar(id, metodo, pedido.getValue());
        assertThat(resposta)
            .as(pedido.getKey())
            .isEqualTo(
                erro(
                    id,
                    ErrorCodes.invalidPayload(metodo, campo + " passa de 4096 bytes em UTF-8")));
        assertThat(resposta).doesNotContain("bbbb").doesNotContain(E_AGUDO + E_AGUDO);
      }
    }
    final List<ObjectNode> noTetoPassam =
        List.of(
            comAnalise(DESCONHECIDA, "{}").put("elementId", noTeto),
            comAnalise(DESCONHECIDA, "{\"direction\":\"IN\"}").put("elementId", noTeto),
            comAnalise(DESCONHECIDA, "{\"target\":\"type:a.C\"}").put("source", noTeto),
            comAnalise(DESCONHECIDA, "{\"source\":\"type:a.C\"}").put("target", noTeto));
    final List<String> metodos =
        List.of(
            "element.describe",
            "element.dependencies",
            "element.dependencyEvidence",
            "element.dependencyEvidence");
    for (int i = 0; i < metodos.size(); i++) {
      final String id = novoId();
      assertThat(despachar(id, metodos.get(i), noTetoPassam.get(i)))
          .as(metodos.get(i) + ": no teto o id passa e o pedido para na prontidão")
          .isEqualTo(erro(id, ErrorCodes.analysisNotFound()));
    }
  }

  @Test
  void umCursorDeDependenciasAcimaDoTetoEMalformado() throws Exception {
    montar(planoComGrafo());
    final String id = novoId();
    final ObjectNode payload =
        comAnalise(DESCONHECIDA, "{\"direction\":\"IN\",\"elementId\":\"type:a.B\"}");
    payload.put("cursor", "A".repeat(AnalysisConstants.MAX_CURSOR_CHARS + 1));

    assertThat(despachar(id, "element.dependencies", payload))
        .isEqualTo(
            erro(
                id, ErrorCodes.invalidPayload("element.dependencies", "o cursor está malformado")));
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
  void umaConsultaComPontoCasaPeloUltimoSegmento() throws Exception {
    final String analise = pronta();
    final String id = novoId();
    final String coreSpi =
        "{\"results\":[{\"elementId\":\"pkg:br.pucpr.legacy.core.spi\",\"kind\":\"PACKAGE\","
            + "\"label\":\"core.spi\",\"packageId\":null,"
            + "\"qualifiedName\":\"br.pucpr.legacy.core.spi\"}]}";

    assertThat(despachar(id, "graph.search", comAnalise(analise, "{\"query\":\"core.spi\"}")))
        .isEqualTo(ok(id, coreSpi));
    for (final String consulta : List.of("a.", ".")) {
      final String vazio = novoId();
      assertThat(despachar(vazio, "graph.search", comAnalise(analise, "{}").put("query", consulta)))
          .as(consulta)
          .isEqualTo(ok(vazio, "{\"results\":[]}"));
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
  void umElementoQueNaoEInternoEElementNotFoundNasLeiturasDoElemento() throws Exception {
    final String analise = pronta();
    final List<String> ausentes =
        List.of(
            "pkg:br.pucpr.legacy.nowhere",
            "type:br.pucpr.legacy.core.Nowhere",
            "type:java.lang.String",
            "pkg:java.lang");

    for (final String ausente : ausentes) {
      final String id = novoId();
      assertThat(
              despachar(
                  id, "element.describe", comAnalise(analise, "{}").put("elementId", ausente)))
          .as("element.describe de " + ausente)
          .isEqualTo(erro(id, ELEMENT_NOT_FOUND));
      for (final String sentido : List.of("IN", "OUT")) {
        final String pagina = novoId();
        final ObjectNode payload =
            comAnalise(analise, "{}").put("direction", sentido).put("elementId", ausente);
        assertThat(despachar(pagina, "element.dependencies", payload))
            .as("element.dependencies " + sentido + " de " + ausente)
            .isEqualTo(erro(pagina, ELEMENT_NOT_FOUND));
      }
    }
    final String kind = "type:br.pucpr.legacy.core.Kind";
    for (final String[] par :
        List.of(
            new String[] {"type:br.pucpr.legacy.core.Nowhere", kind},
            new String[] {"type:java.lang.String", kind},
            new String[] {"type:br.pucpr.legacy.core.CatalogEntry", "type:java.lang.Nowhere"})) {
      final String id = novoId();
      final ObjectNode payload =
          comAnalise(analise, "{}").put("source", par[0]).put("target", par[1]);
      assertThat(despachar(id, "element.dependencyEvidence", payload))
          .as(par[0] + " -> " + par[1])
          .isEqualTo(erro(id, ELEMENT_NOT_FOUND));
    }
    final String semPar = novoId();
    assertThat(
            despachar(
                semPar,
                "element.dependencyEvidence",
                comAnalise(analise, "{}")
                    .put("source", kind)
                    .put("target", "type:br.pucpr.legacy.core.Catalog")))
        .as("Kind não depende de Catalog: o par nunca foi emitido")
        .isEqualTo(
            erro(
                semPar,
                ErrorCodes.invalidPayload(
                    "element.dependencyEvidence", "o par não tem dependência")));
    final JsonNode externo =
        JSON.readTree(
            despachar(
                novoId(),
                "element.dependencyEvidence",
                comAnalise(analise, "{}")
                    .put("source", "type:br.pucpr.legacy.api.Main")
                    .put("target", "type:java.lang.String")));
    assertThat(externo.path("ok").asBoolean())
        .as("o destino externo é servido: " + externo)
        .isTrue();
  }

  @Test
  void umCursorEscritoAMaoRetomaDepoisDaLinhaQueEleNomeia() throws Exception {
    final String analise = pronta();
    final Map<String, Long> pk = chaves(analise);
    final String catalogEntry = "type:br.pucpr.legacy.core.CatalogEntry";
    final JsonNode entrada = payloadDaFixture("element.dependencies.catalog-entry-in.jsonl");
    final JsonNode saida = payloadDaFixture("element.dependencies.catalog-entry-out.jsonl");
    final long ancora = pk.get(catalogEntry);
    final String depoisDoEndpoint =
        cursor(0, ancora, pk.get("type:br.pucpr.legacy.api.CatalogEndpoint"));

    final JsonNode uma = paginaServida(analise, catalogEntry, "IN", depoisDoEndpoint, 1);
    final ObjectNode esperada = JSON.createObjectNode();
    esperada.putArray("items").add(entrada.path("items").get(1));
    esperada.put(
        "nextCursor", cursor(0, ancora, pk.get("type:br.pucpr.legacy.core.spi.MemoryStore")));
    esperada.set("totals", entrada.path("totals"));
    assertThat(uma).as("retoma depois de CatalogEndpoint, uma linha").isEqualTo(esperada);

    final JsonNode resto = paginaServida(analise, catalogEntry, "IN", depoisDoEndpoint, 3);
    final ObjectNode tresUltimas = JSON.createObjectNode();
    final ArrayNode itens = tresUltimas.putArray("items");
    for (int i = 1; i < 4; i++) {
      itens.add(entrada.path("items").get(i));
    }
    tresUltimas.putNull("nextCursor");
    tresUltimas.set("totals", entrada.path("totals"));
    assertThat(resto).as("as três que sobram, e nada depois").isEqualTo(tresUltimas);

    final JsonNode externa =
        paginaServida(
            analise,
            catalogEntry,
            "OUT",
            cursor(1, ancora, pk.get("type:br.pucpr.legacy.core.Kind")),
            1);
    final ObjectNode soAExterna = JSON.createObjectNode();
    soAExterna.putArray("items").add(saida.path("items").get(1));
    soAExterna.putNull("nextCursor");
    soAExterna.set("totals", saida.path("totals"));
    assertThat(externa).as("depois do último interno, o externo").isEqualTo(soAExterna);

    final Map<String, String> estranhos = new LinkedHashMap<>();
    estranhos.put(
        "o sentido OUT em um pedido IN",
        cursor(1, ancora, pk.get("type:br.pucpr.legacy.api.CatalogEndpoint")));
    estranhos.put(
        "a âncora de Catalog",
        cursor(
            0,
            pk.get("type:br.pucpr.legacy.core.Catalog"),
            pk.get("type:br.pucpr.legacy.api.CatalogEndpoint")));
    estranhos.put(
        "Kind, que não está na entrada",
        cursor(0, ancora, pk.get("type:br.pucpr.legacy.core.Kind")));
    estranhos.put("uma chave que não existe", cursor(0, ancora, 999_999_999L));
    estranhos.put(
        "Catalog, a última linha da entrada, que o Java nunca cunha",
        cursor(0, ancora, pk.get("type:br.pucpr.legacy.core.Catalog")));
    for (final Map.Entry<String, String> estranho : estranhos.entrySet()) {
      final JsonNode recusa = pagina(analise, catalogEntry, "IN", estranho.getValue(), 1);
      assertThat(recusa.path("error"))
          .as(estranho.getKey())
          .isEqualTo(
              JSON.readTree(
                      bytes(
                          Envelope.error(
                              "x",
                              ErrorCodes.invalidPayload(
                                  "element.dependencies",
                                  "o cursor não aponta para uma linha desta listagem"))))
                  .path("error"));
    }
  }

  private static final int DEPENDENTES = 2_001;

  private static final int OCORRENCIAS = 25;

  private Path repositorioDosTetos() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("tetos");
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(
          repositorio, "src/main/java/app/Centro.java", "package app;\n\npublic class Centro {}\n");
      final StringBuilder muitos = new StringBuilder("package app;\n\npublic class Muitos {\n");
      for (int i = 0; i < DEPENDENTES; i++) {
        muitos.append("  static class N%04d {\n    Centro c;\n  }\n".formatted(i));
      }
      TestRepositories.write(repositorio, "src/main/java/app/Muitos.java", muitos + "}\n");
      TestRepositories.write(
          repositorio,
          "src/main/java/app/Alvo.java",
          "package app;\n\npublic class Alvo {\n  public void m() {}\n}\n");
      final StringBuilder fonte = new StringBuilder("package app;\n\npublic class Fonte {\n");
      final StringBuilder chamadas = new StringBuilder("  void chama() {\n");
      for (int i = 0; i < OCORRENCIAS; i++) {
        fonte.append("  Alvo a%02d;\n".formatted(i));
        fonte.append("  void p%02d(Alvo x) {}\n".formatted(i));
        chamadas.append("    a%02d.m();\n".formatted(i));
      }
      fonte.append(chamadas).append("  }\n}\n");
      TestRepositories.write(repositorio, "src/main/java/app/Fonte.java", fonte.toString());
      TestRepositories.commitAll(git, "tetos\n");
    }
    return repositorio;
  }

  private static JsonNode limiteAbaixoDeUm(String metodo) throws IOException {
    return JSON.readTree(
            bytes(
                Envelope.error(
                    "x", ErrorCodes.invalidPayload(metodo, "limit deve ser pelo menos 1"))))
        .path("error");
  }

  private JsonNode paginaSemLimite(String analise, String elemento, String sentido, String cursor)
      throws IOException {
    final ObjectNode payload =
        comAnalise(analise, "{}")
            .put("cursor", cursor)
            .put("direction", sentido)
            .put("elementId", elemento);
    final JsonNode resposta = JSON.readTree(despachar(novoId(), "element.dependencies", payload));
    assertThat(resposta.path("ok").asBoolean()).as(resposta.toString()).isTrue();
    return resposta.path("payload");
  }

  private JsonNode evidencia(String analise, String origem, String destino, Integer limite)
      throws IOException {
    final ObjectNode payload =
        comAnalise(analise, "{}").put("source", origem).put("target", destino);
    if (limite != null) {
      payload.put("limit", limite);
    }
    return JSON.readTree(despachar(novoId(), "element.dependencyEvidence", payload));
  }

  @Test
  void osTetosDaPaginaDeDependenciasValemPeloFio() throws Exception {
    montar(planoComGrafo());
    final String analise = comecar(repositorioDosTetos());
    assertThat(quadros.terminal(analise)).isEqualTo("analysis.completed");
    final String centro = "type:app.Centro";

    final JsonNode padrao = paginaSemLimite(analise, centro, "IN", null);
    assertThat(padrao.path("items")).hasSize(500);
    assertThat(padrao.path("nextCursor").isTextual()).isTrue();
    assertThat(padrao.path("totals").path("in").asInt()).isEqualTo(DEPENDENTES);
    assertThat(paginaServida(analise, centro, "IN", null, 5_000).path("items")).hasSize(2_000);
    assertThat(paginaServida(analise, centro, "IN", null, 2_001).path("items")).hasSize(2_000);
    assertThat(pagina(analise, centro, "IN", null, 0).path("error"))
        .isEqualTo(limiteAbaixoDeUm("element.dependencies"));

    final List<Integer> tamanhos = new ArrayList<>();
    final Set<String> vistas = new HashSet<>();
    String cursor = null;
    do {
      final JsonNode pagina = paginaSemLimite(analise, centro, "IN", cursor);
      tamanhos.add(pagina.path("items").size());
      pagina.path("items").forEach(item -> vistas.add(item.path("elementId").asText()));
      assertThat(pagina.path("totals")).isEqualTo(padrao.path("totals"));
      cursor = pagina.path("nextCursor").isNull() ? null : pagina.path("nextCursor").asText();
    } while (cursor != null && tamanhos.size() < 10);
    assertThat(tamanhos).containsExactly(500, 500, 500, 500, 1);
    assertThat(vistas).hasSize(DEPENDENTES);
  }

  @Test
  void oTetoDaEvidenciaValePeloFio() throws Exception {
    montar(planoComGrafo());
    final String analise = comecar(repositorioDosTetos());
    assertThat(quadros.terminal(analise)).isEqualTo("analysis.completed");

    final JsonNode semLimite = evidencia(analise, "type:app.Fonte", "type:app.Alvo", null);
    final JsonNode cinquentaEUm = evidencia(analise, "type:app.Fonte", "type:app.Alvo", 51);
    final JsonNode zero = evidencia(analise, "type:app.Fonte", "type:app.Alvo", 0);

    assertThat(semLimite.path("ok").asBoolean()).as(semLimite.toString()).isTrue();
    assertThat(semLimite.path("payload").path("evidence")).hasSize(50);
    final Set<String> tipos = new TreeSet<>();
    semLimite.path("payload").path("evidence").forEach(e -> tipos.add(e.path("type").asText()));
    assertThat(tipos).containsExactly("CALLS", "FIELD_TYPE", "PARAMETER_TYPE");
    assertThat(cinquentaEUm.path("payload")).isEqualTo(semLimite.path("payload"));
    assertThat(zero.path("error")).isEqualTo(limiteAbaixoDeUm("element.dependencyEvidence"));
  }

  private static String cursor(int sentido, long ancora, long ultima) {
    final ByteBuffer bytes = ByteBuffer.allocate(18);
    bytes.put((byte) 2).put((byte) sentido).putLong(ancora).putLong(ultima);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
  }

  private JsonNode paginaServida(
      String analise, String elemento, String sentido, String cursor, int limite)
      throws IOException {
    final JsonNode resposta = pagina(analise, elemento, sentido, cursor, limite);
    assertThat(resposta.path("ok").asBoolean()).as(resposta.toString()).isTrue();
    return resposta.path("payload");
  }

  private JsonNode pagina(
      String analise, String elemento, String sentido, String cursor, int limite)
      throws IOException {
    final ObjectNode payload =
        comAnalise(analise, "{}")
            .put("cursor", cursor)
            .put("direction", sentido)
            .put("elementId", elemento)
            .put("limit", limite);
    return JSON.readTree(despachar(novoId(), "element.dependencies", payload));
  }

  private Map<String, Long> chaves(String analise) throws SQLException {
    final ReadableAnalysis legivel = registro.readable(new AnalysisId(analise)).orElseThrow();
    return legivel
        .connections()
        .read(
            conexao -> {
              final Map<String, Long> chaves = new HashMap<>();
              try (PreparedStatement consulta =
                  conexao.prepareStatement(
                      "SELECT element_id, id FROM code_element WHERE analysis_pk = ?")) {
                consulta.setLong(1, legivel.analysisPk());
                try (ResultSet linha = consulta.executeQuery()) {
                  while (linha.next()) {
                    chaves.put(linha.getString(1), linha.getLong(2));
                  }
                }
              }
              return Map.copyOf(chaves);
            });
  }

  private static JsonNode payloadDaFixture(String arquivo) throws IOException {
    final Path pasta = Path.of(System.getProperty("strata.protocol.fixtures"));
    final List<String> linhas = Files.readAllLines(pasta.resolve(arquivo), UTF_8);
    return JSON.readTree(linhas.get(1)).path("payload");
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

  @Test
  void aFraseNomeiaOCampoEONuloExplicitoEObrigatorio() {
    final String metodo = "element.describe";
    final String acima = "type:" + "a".repeat(AnalysisConstants.MAX_ELEMENT_ID_BYTES);

    assertThat(recusa(() -> StructureReads.elementId(metodo, "elementId", acima)))
        .isEqualTo(ErrorCodes.invalidPayload(metodo, "elementId passa de 4096 bytes em UTF-8"));
    assertThat(recusa(() -> StructureReads.elementId(metodo, "source", "pfx:a")))
        .isEqualTo(ErrorCodes.invalidPayload(metodo, "source não é um id de elemento"));
    assertThat(recusa(() -> StructureReads.required(metodo, "target", null)))
        .isEqualTo(ErrorCodes.invalidPayload(metodo, "target é obrigatório"));
    assertThat(StructureReads.required(metodo, "elementId", "type:a.B")).isEqualTo("type:a.B");
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
    assertThat(StructureReads.elementId(metodo, "parentId", null)).isNull();
    final String noTeto = "pkg:" + "a".repeat(teto - 4);
    assertThat(StructureReads.elementId(metodo, "parentId", noTeto))
        .isEqualTo(new CodeElementId(noTeto));
    assertThat(StructureReads.elementId(metodo, "parentId", "pkg:"))
        .isEqualTo(new CodeElementId("pkg:"));
    final Map<String, String> fraseDe =
        Map.of(
            noTeto + "a",
            "parentId passa de 4096 bytes em UTF-8",
            "pfx:br.pucpr",
            "parentId não é um id de elemento",
            "nope",
            "parentId não é um id de elemento");
    for (final Map.Entry<String, String> caso : fraseDe.entrySet()) {
      final String recusado = caso.getKey();
      final ProtocolError erro =
          recusa(() -> StructureReads.elementId(metodo, "parentId", recusado));
      assertThat(erro).as(recusado).isEqualTo(ErrorCodes.invalidPayload(metodo, caso.getValue()));
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
