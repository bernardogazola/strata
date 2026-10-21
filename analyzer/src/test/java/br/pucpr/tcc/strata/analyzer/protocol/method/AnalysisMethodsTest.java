package br.pucpr.tcc.strata.analyzer.protocol.method;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.RegistrySettings;
import br.pucpr.tcc.strata.analyzer.analysis.SkippedStage;
import br.pucpr.tcc.strata.analyzer.analysis.StageCounters;
import br.pucpr.tcc.strata.analyzer.analysis.StageExecutor;
import br.pucpr.tcc.strata.analyzer.analysis.StageOutcome;
import br.pucpr.tcc.strata.analyzer.analysis.StagePlan;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlite.SQLiteConfig;

final class AnalysisMethodsTest {

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final String FIXTURE_ID = "an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W";

  private static final Duration AWAIT = Duration.ofSeconds(60);

  private static final String DEFAULT_CONFIG_JSON =
      "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":false,"
          + "\"sourceRootIds\":null}";

  private static final String DEFAULT_CONFIG_HASH =
      "c8ddc5aec20c88456feb94818eb0c8fe6c9838069031742b38a6b5dc4edffb1d";

  private static final ObjectMapper JSON = new ObjectMapper();

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

    synchronized List<JsonNode> todos() {
      return List.copyOf(recebidos);
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

    JsonNode terminal(String analysisId) throws InterruptedException {
      return esperar(
          q ->
              analysisId.equals(q.path("payload").path("analysisId").asText())
                  && Arrays.asList("analysis.completed", "analysis.cancelled", "analysis.failed")
                      .contains(q.path("event").asText()),
          "o evento terminal de " + analysisId);
    }
  }

  private static AnalysisId idNumero(int n) {
    final int sufixo = 60 + n;
    return new AnalysisId(
        "an_01JQ8Z9K3M4N5P6Q7R8S9T0V"
            + CROCKFORD.charAt(sufixo / 32 % 32)
            + CROCKFORD.charAt(sufixo % 32));
  }

  private static void esperarAThread(String analysisId) throws InterruptedException {
    final String nome = "strata-analysis-" + analysisId.substring(analysisId.length() - 8);
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (nome.equals(thread.getName())) {
        thread.join(AWAIT.toMillis());
        assertThat(thread.isAlive()).as(nome + " ainda vive").isFalse();
      }
    }
  }

  private static StagePlan planoLeve() {
    final Map<AnalysisStage, StageExecutor> executores = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : StagePlan.SPI_STAGES) {
      executores.put(stage, SkippedStage.INSTANCE);
    }
    executores.put(
        AnalysisStage.PERSISTENCE, context -> StageOutcome.completed(StageCounters.none()));
    return new StagePlan(executores);
  }

  private StagePlan planoQuePrende(CountDownLatch dentro) {
    return planoLeve()
        .with(
            AnalysisStage.STRUCTURAL_GRAPH,
            context -> {
              dentro.countDown();
              final CountDownLatch nunca = new CountDownLatch(1);
              trincos.add(nunca);
              final long prazo = System.nanoTime() + AWAIT.toNanos();
              while (System.nanoTime() < prazo) {
                context.cancellation().throwIfCancelled();
                try {
                  if (nunca.await(10, TimeUnit.MILLISECONDS)) {
                    break;
                  }
                } catch (InterruptedException interrompido) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrompido);
                }
              }
              return StageOutcome.skipped();
            });
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

  private Dispatcher.Outcome.Respond despachar(
      String id, String metodo, String payload, String repositoryPath) throws IOException {
    final ObjectNode quadro = inbound.createObjectNode();
    quadro.put("id", id).put("kind", "request").put("method", metodo);
    quadro.set("payload", JSON.readTree(payload));
    quadro.put("protocol", 1);
    if (repositoryPath != null) {
      quadro.put("repositoryPath", repositoryPath);
    }
    final Dispatcher.Outcome fora =
        dispatcher.dispatch(dispatcher.parse(JSON.writeValueAsString(quadro).getBytes(UTF_8)));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return (Dispatcher.Outcome.Respond) fora;
  }

  private static JsonNode comoJson(Dispatcher.Outcome.Respond resposta) throws IOException {
    return JSON.readTree(new CanonicalJson().toBytes(resposta.envelope()));
  }

  private static String bytes(Envelope envelope) {
    return new String(new CanonicalJson().toBytes(envelope), UTF_8);
  }

  private String comecar(String payload, Path repositorio) throws IOException {
    final Dispatcher.Outcome.Respond resposta =
        despachar(novoId(), "analysis.start", payload, repositorio.toString());
    final JsonNode lida = comoJson(resposta);
    assertThat(lida.path("ok").asBoolean()).as(lida.toString()).isTrue();
    final JsonNode carga = lida.path("payload");
    assertThat(fieldNames(carga)).containsExactlyInAnyOrder("analysisId", "reusedCache");
    assertThat(carga.path("reusedCache").asBoolean())
        .as("reusedCache é falso, por construção")
        .isFalse();
    assertThat(carga.path("reusedCache").isBoolean()).isTrue();
    resposta.afterEmit().run();
    return carga.path("analysisId").asText();
  }

  private static List<String> fieldNames(JsonNode objeto) {
    final List<String> nomes = new ArrayList<>();
    objeto.fieldNames().forEachRemaining(nomes::add);
    return nomes;
  }

  private Map<String, Object> linhaDaAnalise(Path repositorio, String commit, String analysisId)
      throws Exception {
    final Path banco =
        dados
            .resolve(PersistenceRoot.DIRECTORY_NAME)
            .resolve(
                RepositoryIdentity.of(repositorio.toString(), PathFlavor.host(), commit)
                    .key()
                    .value())
            .resolve(RepositoryCache.DATABASE_FILE);
    final SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    try (Connection conexao =
            DriverManager.getConnection("jdbc:sqlite:" + banco, config.toProperties());
        PreparedStatement consulta =
            conexao.prepareStatement("SELECT * FROM analysis WHERE analysis_id = ?")) {
      consulta.setString(1, analysisId);
      try (ResultSet linha = consulta.executeQuery()) {
        assertThat(linha.next()).as("a linha analysis de " + analysisId).isTrue();
        final Map<String, Object> colunas = new LinkedHashMap<>();
        for (int i = 1; i <= linha.getMetaData().getColumnCount(); i++) {
          colunas.put(linha.getMetaData().getColumnLabel(i), linha.getObject(i));
        }
        return colunas;
      }
    }
  }

  private static String sha256(String texto) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(UTF_8)));
  }

  private Map<String, Object> gravada(String payload) throws Exception {
    montar(planoLeve());
    final Path repositorio = multiModulo();
    final String id = comecar(payload, repositorio);
    assertThat(quadros.terminal(id).path("event").asText()).isEqualTo("analysis.completed");
    return linhaDaAnalise(repositorio, TestRepositories.corpusCommitId("multi-module"), id);
  }

  @Test
  void umPayloadVazioValeOsPadroesEGravaOVetorUmDeK4() throws Exception {
    final Map<String, Object> linha = gravada("{}");

    assertThat(((Number) linha.get("include_tests")).intValue()).isZero();
    assertThat(linha.get("history_scope")).isEqualTo("ALL");
    assertThat(linha.get("history_scope_commits")).isNull();
    assertThat(linha.get("config_json")).isEqualTo(DEFAULT_CONFIG_JSON);
    assertThat(linha.get("config_hash")).isEqualTo(DEFAULT_CONFIG_HASH);
  }

  @Test
  void historyScopeCommitsDeTresBilhoesCortaEm200MilNaLinhaENoConfigJson() throws Exception {
    final Map<String, Object> linha =
        gravada("{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":3000000000}");

    final String esperado =
        "{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":200000,"
            + "\"includeTests\":false,\"sourceRootIds\":null}";
    assertThat(((Number) linha.get("history_scope_commits")).longValue()).isEqualTo(200_000L);
    assertThat(linha.get("config_json")).isEqualTo(esperado);
    assertThat(linha.get("config_hash")).isEqualTo(sha256(esperado));
  }

  @Test
  void allComNDescartaON() throws Exception {
    final Map<String, Object> linha =
        gravada("{\"historyScope\":\"ALL\",\"historyScopeCommits\":50}");

    assertThat(linha.get("history_scope_commits")).isNull();
    assertThat(linha.get("config_json")).isEqualTo(DEFAULT_CONFIG_JSON);
  }

  @Test
  void idsRepetidosViramUmOrdenadosEAListaEASelecao() throws Exception {
    final Map<String, Object> linha =
        gravada(
            "{\"includeTests\":true,"
                + "\"sourceRootIds\":[\"sr_sqbycfms\",\"sr_3gwc7d6d\",\"sr_sqbycfms\"]}");

    final String esperado =
        "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":true,"
            + "\"sourceRootIds\":[\"sr_3gwc7d6d\",\"sr_sqbycfms\"]}";
    assertThat(linha.get("config_json")).isEqualTo(esperado);
    assertThat(linha.get("config_hash")).isEqualTo(sha256(esperado));
    assertThat(((Number) linha.get("include_tests")).intValue()).isOne();
    assertThat(((Number) linha.get("total_files")).intValue())
        .as("api (2) + core main (6): a TEST não entra, includeTests só molda o padrão")
        .isEqualTo(8);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"historyScope\":\"LAST_N_COMMITS\"}|LAST_N_COMMITS exige historyScopeCommits",
        "{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":0}"
            + "|historyScopeCommits deve ser pelo menos 1",
        "{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":-5}"
            + "|historyScopeCommits deve ser pelo menos 1",
        "{\"sourceRootIds\":[]}|sourceRootIds não pode ser vazio",
        "{\"sourceRootIds\":[null]}|sourceRootIds contém um id de raiz de código malformado",
        "{\"sourceRootIds\":[\"sr_SEGREDO1\"]}|sourceRootIds contém um id de raiz de código malformado",
        "{\"sourceRootIds\":[\"sr_3gwc7d6d\",\"../x\"]}"
            + "|sourceRootIds contém um id de raiz de código malformado"
      })
  void cadaRecusaDeK2TemFraseFixaSemEcoENaoRegistraNada(String payload, String detail)
      throws Exception {
    montar(planoLeve());
    final Path repositorio = multiModulo();
    final String id = novoId();

    final Dispatcher.Outcome.Respond resposta =
        despachar(id, "analysis.start", payload, repositorio.toString());

    assertThat(bytes(resposta.envelope()))
        .isEqualTo(bytes(Envelope.error(id, ErrorCodes.invalidPayload("analysis.start", detail))));
    resposta.afterEmit().run();
    assertThat(quadros.todos()).as("uma recusa síncrona não emite evento nenhum").isEmpty();
    final String aceito = comecar("{}", repositorio);
    assertThat(quadros.terminal(aceito).path("event").asText())
        .as("a recusa não ocupou a vaga")
        .isEqualTo("analysis.completed");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      nullValues = "AUSENTE",
      value = {"AUSENTE", "repo", "/tmp/a/../b"})
  void umRepositoryPathQueORustNuncaEscreveEPayloadInvalidoComAFraseDoDescribe(String caminho)
      throws Exception {
    montar(planoLeve());

    final JsonNode doStart = comoJson(despachar(novoId(), "analysis.start", "{}", caminho));
    final JsonNode doDescribe = comoJson(despachar(novoId(), "repository.describe", "{}", caminho));

    assertThat(doStart.path("ok").asBoolean()).isFalse();
    assertThat(doStart.path("error").path("code").asText())
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(doStart.path("error").path("params"))
        .isEqualTo(JSON.readTree("{\"method\":\"analysis.start\"}"));
    assertThat(doStart.path("error").path("detail"))
        .as("a regra do ClaimedRoot, extraída e compartilhada")
        .isEqualTo(doDescribe.path("error").path("detail"));
  }

  @Test
  void umSegundoStartComUmaAnaliseRodandoEAnalysisAlreadyRunning() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoQuePrende(dentro));
    final Path repositorio = multiModulo();
    final String primeira = comecar("{}", repositorio);
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

    final String segundo = novoId();
    assertThat(bytes(despachar(segundo, "analysis.start", "{}", repositorio.toString()).envelope()))
        .isEqualTo(bytes(Envelope.error(segundo, ErrorCodes.analysisAlreadyRunning())));
    final JsonNode invalido =
        comoJson(
            despachar(
                novoId(),
                "analysis.start",
                "{\"historyScope\":\"LAST_N_COMMITS\"}",
                repositorio.toString()));
    assertThat(invalido.path("error").path("code").asText())
        .as("a forma do payload é conferida antes da vaga")
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);

    final Dispatcher.Outcome.Respond cancelar =
        despachar(novoId(), "analysis.cancel", "{\"analysisId\":\"" + primeira + "\"}", null);
    cancelar.afterEmit().run();
    assertThat(quadros.terminal(primeira).path("event").asText()).isEqualTo("analysis.cancelled");
  }

  @Test
  void cancelarRespondeVerdadeiroSoParaUmaAnaliseRodandoEOGanchoEQuemCancela() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoQuePrende(dentro));
    final Path repositorio = multiModulo();
    final String id = comecar("{}", repositorio);
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    final String pedido = "{\"analysisId\":\"" + id + "\"}";

    final Dispatcher.Outcome.Respond primeiro =
        despachar(novoId(), "analysis.cancel", pedido, null);
    assertThat(comoJson(primeiro).path("payload")).isEqualTo(JSON.readTree("{\"cancelled\":true}"));
    assertThat(primeiro.afterEmit())
        .as("o cancelamento do token é o gancho, não o handle")
        .isNotSameAs(Dispatcher.Outcome.Respond.NOTHING);
    final Dispatcher.Outcome.Respond segundo = despachar(novoId(), "analysis.cancel", pedido, null);
    assertThat(comoJson(segundo).path("payload")).isEqualTo(JSON.readTree("{\"cancelled\":true}"));
    assertThat(quadros.todos().stream().map(q -> q.path("event").asText()))
        .as("antes do gancho, a análise segue: nenhum evento terminal")
        .doesNotContain("analysis.cancelled");

    primeiro.afterEmit().run();
    segundo.afterEmit().run();
    assertThat(quadros.terminal(id).path("event").asText()).isEqualTo("analysis.cancelled");

    assertThat(comoJson(despachar(novoId(), "analysis.cancel", pedido, null)).path("payload"))
        .as("terminada → falso")
        .isEqualTo(JSON.readTree("{\"cancelled\":false}"));
    assertThat(
            comoJson(
                    despachar(
                        novoId(),
                        "analysis.cancel",
                        "{\"analysisId\":\"" + idNumero(400).value() + "\"}",
                        null))
                .path("payload"))
        .as("desconhecida → falso")
        .isEqualTo(JSON.readTree("{\"cancelled\":false}"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "analysis.cancel|{\"analysisId\":\"an_SEGREDO\"}",
        "analysis.cancel|{}",
        "analysis.cancel|{\"analysisId\":null}",
        "analysis.status|{\"analysisId\":\"an_SEGREDO\"}",
        "analysis.status|{}"
      })
  void umAnalysisIdMalformadoOuAusenteEPayloadInvalidoSemEco(String metodo, String payload)
      throws Exception {
    montar(planoLeve());

    final JsonNode resposta = comoJson(despachar(novoId(), metodo, payload, null));

    assertThat(resposta.path("error").path("code").asText())
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(resposta.path("error").path("params"))
        .isEqualTo(JSON.readTree("{\"method\":\"" + metodo + "\"}"));
    assertThat(resposta.toString()).doesNotContain("SEGREDO");
  }

  @Test
  void oStatusDeUmIdDesconhecidoEAnalysisNotFound() throws Exception {
    montar(planoLeve());
    final String id = novoId();

    final Dispatcher.Outcome.Respond resposta =
        despachar(id, "analysis.status", "{\"analysisId\":\"" + FIXTURE_ID + "\"}", null);

    assertThat(bytes(resposta.envelope()))
        .isEqualTo(bytes(Envelope.error(id, ErrorCodes.analysisNotFound())));
  }

  private JsonNode status(String analysisId) throws IOException {
    final JsonNode resposta =
        comoJson(
            despachar(
                novoId(), "analysis.status", "{\"analysisId\":\"" + analysisId + "\"}", null));
    assertThat(resposta.path("ok").asBoolean()).as(resposta.toString()).isTrue();
    return resposta.path("payload");
  }

  private static void verificarForma(JsonNode relatorio) {
    assertThat(fieldNames(relatorio))
        .containsExactlyInAnyOrder(
            "analysisId",
            "completeness",
            "error",
            "finishedAt",
            "headCommitId",
            "stages",
            "startedAt",
            "status");
    assertThat(relatorio.path("startedAt").asText())
        .as("ISO-8601 UTC em segundos, o timestampSchema")
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    final List<String> ordem = new ArrayList<>();
    for (final JsonNode estagio : relatorio.path("stages")) {
      assertThat(fieldNames(estagio))
          .containsExactlyInAnyOrder("counters", "durationMs", "stage", "startedAt", "status");
      ordem.add(estagio.path("stage").asText());
    }
    assertThat(ordem)
        .as("os dez estágios, na ordem do pipeline")
        .containsExactlyElementsOf(Arrays.stream(AnalysisStage.values()).map(Enum::name).toList());
  }

  @Test
  void oStatusDeUmaAnaliseCompletaTemOsDezEstagiosEErroNulo() throws Exception {
    montar(planoLeve());
    final Path repositorio = multiModulo();
    final String id = comecar("{}", repositorio);
    assertThat(quadros.terminal(id).path("event").asText()).isEqualTo("analysis.completed");

    final JsonNode completa = status(id);

    verificarForma(completa);
    assertThat(completa.path("analysisId").asText()).isEqualTo(id).isEqualTo(FIXTURE_ID);
    assertThat(completa.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(completa.path("error").isNull()).isTrue();
    assertThat(completa.path("finishedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
    assertThat(completa.path("startedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
    assertThat(completa.path("headCommitId").asText())
        .isEqualTo(TestRepositories.corpusCommitId("multi-module"));
    for (final JsonNode estagio : completa.path("stages")) {
      assertThat(estagio.path("durationMs").asLong())
          .as(estagio.path("stage").asText() + ": relógio monotônico congelado")
          .isZero();
      assertThat(estagio.path("startedAt").asText()).isEqualTo("2024-01-02T03:04:05Z");
    }
  }

  @Test
  void oStatusReportaRunningCompletedCancelledEFailedComErroSoNoFailed() throws Exception {
    final CountDownLatch dentro = new CountDownLatch(1);
    montar(planoQuePrende(dentro));
    final Path repositorio = multiModulo();
    final String commit = TestRepositories.corpusCommitId("multi-module");

    final String rodando = comecar("{}", repositorio);
    assertThat(dentro.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    final JsonNode emCurso = status(rodando);
    verificarForma(emCurso);
    assertThat(emCurso.path("status").asText()).isEqualTo("RUNNING");
    assertThat(emCurso.path("finishedAt").isNull()).isTrue();
    assertThat(emCurso.path("error").isNull()).isTrue();
    assertThat(emCurso.path("headCommitId").asText()).isEqualTo(commit);
    final JsonNode pendente = emCurso.path("stages").get(AnalysisStage.PERSISTENCE.ordinal());
    assertThat(pendente.path("status").asText()).isEqualTo("PENDING");
    assertThat(pendente.path("startedAt").isNull()).isTrue();
    assertThat(pendente.path("durationMs").isNull()).isTrue();
    assertThat(pendente.path("counters").size()).isZero();

    despachar(novoId(), "analysis.cancel", "{\"analysisId\":\"" + rodando + "\"}", null)
        .afterEmit()
        .run();
    quadros.terminal(rodando);
    esperarAThread(rodando);
    final JsonNode cancelado = status(rodando);
    verificarForma(cancelado);
    assertThat(cancelado.path("status").asText())
        .as("um cancelado é reportado CANCELLED")
        .isEqualTo("CANCELLED");
    assertThat(cancelado.path("error").isNull()).isTrue();

    final Path semJava = repositorios.toRealPath().resolve("sem-java");
    try (var git = TestRepositories.init(semJava)) {
      TestRepositories.write(semJava, "README.md", "# sem java\n");
      TestRepositories.commitAll(git, "primeiro");
    }
    final String falhou = comecar("{}", semJava);
    quadros.terminal(falhou);
    final JsonNode falha = status(falhou);
    verificarForma(falha);
    assertThat(falha.path("status").asText()).isEqualTo("FAILED");
    assertThat(falha.path("error"))
        .as("um FAILED carrega o erro")
        .isEqualTo(JSON.valueToTree(ErrorCodes.repository(RepositoryIssue.noJavaSources())));
  }
}
