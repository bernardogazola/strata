package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisConfiguration;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.Completeness;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.analysis.ProgressTick;
import br.pucpr.tcc.strata.analyzer.analysis.ProgressUnit;
import br.pucpr.tcc.strata.analyzer.analysis.RegistrySettings;
import br.pucpr.tcc.strata.analyzer.analysis.StageCounters;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.analysis.StartResult;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidationReason;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
          "analysis.start.ok.jsonl",
          "analysis.cancel.unknown.jsonl",
          "analysis.status.not-found.jsonl",
          "analysis.status.completed.jsonl",
          "protocol.invalid-payload.jsonl",
          "analysis.started.ok.jsonl",
          "analysis.progress.known-total.jsonl",
          "analysis.progress.unknown-total.jsonl",
          "analysis.stage.completed.ok.jsonl",
          "analysis.completed.partial.jsonl",
          "analysis.cancelled.ok.jsonl",
          "analysis.failed.no-java.jsonl",
          "analysis.warning.parse-failed.jsonl",
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

  private static final AnalysisId ID_DA_FIXTURE = new AnalysisId("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  private static final Instant INSTANTE_DA_FIXTURE =
      TestRepositories.FIXTURE_IDENT.getWhenAsInstant();

  private static final Duration ESPERA = Duration.ofSeconds(60);

  private final AtomicBoolean encerrar = new AtomicBoolean(false);
  private final ObjectMapper inbound = JsonLines.strictInboundMapper();
  private final CanonicalJson canonical = new CanonicalJson();
  private final List<AnalysisRegistry> registrosQueRodam = new ArrayList<>();

  @TempDir Path dados;

  private Dispatcher dispatcher;

  private static final FrameSink NENHUM_QUADRO =
      envelope -> {
        throw new AssertionError("nenhum quadro sai fora do despachante: " + envelope);
      };

  @BeforeEach
  void montarDespachante() throws IOException {
    dispatcher =
        Dispatcher.of(
            inbound,
            Handlers.all(() -> 0L, encerrar, new AnalysisRegistry(configuracoes(NENHUM_QUADRO))));
  }

  @AfterEach
  void fecharOsRegistros() {
    for (final AnalysisRegistry registro : registrosQueRodam) {
      registro.close();
    }
  }

  private RegistrySettings configuracoes(FrameSink sink) throws IOException {
    return RegistrySettings.production(PersistenceRoot.prepare(dados), sink, VERSAO_DA_FIXTURE)
        .withClock(InstantSource.fixed(INSTANTE_DA_FIXTURE))
        .withNanoClock(() -> 0L)
        .withIds(() -> ID_DA_FIXTURE);
  }

  private AnalysisRegistry registroQueRoda(FrameSink sink) throws IOException {
    final AnalysisRegistry registro = new AnalysisRegistry(configuracoes(sink));
    registrosQueRodam.add(registro);
    return registro;
  }

  private String emitido(Envelope envelope) {
    return new String(canonical.toBytes(envelope), StandardCharsets.UTF_8);
  }

  private Dispatcher.Outcome despachar(String linha) throws IOException {
    return dispatcher.dispatch(dispatcher.parse(linha.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aPastaDeFixturesExisteEFoiLida() throws IOException {
    assertThat(FIXTURES).isDirectory();
    assertThat(arquivos()).contains("protocol.ready.mvp.jsonl").hasSize(29);
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
    return despacharEmRepositorio(dispatcher, arquivo, repositorio);
  }

  private String despacharEmRepositorio(Dispatcher despachante, String arquivo, Path repositorio)
      throws IOException {
    final List<String> l = linhas(arquivo);
    assertThat(l).as(arquivo + ": requisição e resposta").hasSize(2);
    final ObjectNode requisicao = (ObjectNode) inbound.readTree(l.get(0));
    assertThat(requisicao.has("repositoryPath"))
        .as("a fixture não pode trazer o caminho absoluto da máquina de quem a escreveu")
        .isFalse();
    requisicao.put("repositoryPath", repositorio.toRealPath().toString());
    final Dispatcher.Outcome fora =
        despachante.dispatch(
            despachante.parse(
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
  void analysisStartedBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.started.ok.jsonl");

    assertThat(emitido(AnalysisEvents.started(ID_DA_FIXTURE))).isEqualTo(esperado);
  }

  @Test
  void analysisProgressComTotalBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.progress.known-total.jsonl");

    final String emitido =
        emitido(
            AnalysisEvents.progress(
                ID_DA_FIXTURE,
                new ProgressTick(
                    AnalysisStage.SOURCE_ANALYSIS, ProgressUnit.JAVA_FILES, 1240, 3902L)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisProgressSemTotalBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.progress.unknown-total.jsonl");

    final String emitido =
        emitido(
            AnalysisEvents.progress(
                ID_DA_FIXTURE,
                new ProgressTick(AnalysisStage.GIT_HISTORY_CHEAP, ProgressUnit.COMMITS, 0, null)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisStageCompletedBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.stage.completed.ok.jsonl");
    final Map<String, Long> contadores = new LinkedHashMap<>();
    contadores.put("types", 1284L);
    contadores.put("files", 3902L);

    final String emitido =
        emitido(
            AnalysisEvents.stageCompleted(
                ID_DA_FIXTURE,
                AnalysisStage.SOURCE_ANALYSIS,
                StageStatus.COMPLETED,
                18_450L,
                StageCounters.of(contadores)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisCompletedParcialBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.completed.partial.jsonl");

    final String emitido =
        emitido(
            AnalysisEvents.completed(ID_DA_FIXTURE, new Completeness(3902, 12, 3, false, null)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisCancelledBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.cancelled.ok.jsonl");

    final String emitido =
        emitido(AnalysisEvents.cancelled(ID_DA_FIXTURE, new Completeness(1204, 0, 0, false, null)));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisFailedSemJavaBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.failed.no-java.jsonl");

    final String emitido =
        emitido(
            AnalysisEvents.failed(
                ID_DA_FIXTURE, ErrorCodes.repository(RepositoryIssue.noJavaSources())));

    assertThat(emitido).isEqualTo(esperado);
  }

  @Test
  void analysisWarningParseFailedBateByteAByte() throws IOException {
    final String esperado = unicaLinha("analysis.warning.parse-failed.jsonl");

    final String emitido =
        emitido(
            AnalysisEvents.warning(
                ID_DA_FIXTURE,
                ErrorCodes.source(
                    SourceIssue.parseFailed("src/main/java/br/pucpr/orders/PaymentService.java"))));

    assertThat(emitido).isEqualTo(esperado);
  }

  private String despacharFixture(String arquivo) throws IOException {
    final List<String> l = linhas(arquivo);
    assertThat(l).as(arquivo + ": requisição e resposta").hasSize(2);
    final Dispatcher.Outcome fora = despachar(l.get(0));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    return emitido(((Dispatcher.Outcome.Respond) fora).envelope());
  }

  @Test
  void analysisStartOkBateByteAByteSemRodarOGancho(@TempDir Path temp) throws Exception {
    final Path repositorio = temp.toRealPath().resolve("multi-module");
    TestRepositories.reproduceCorpusFixture("multi-module", repositorio);
    final Dispatcher despachante =
        Dispatcher.of(inbound, Handlers.all(() -> 0L, encerrar, registroQueRoda(NENHUM_QUADRO)));

    assertThat(despacharEmRepositorio(despachante, "analysis.start.ok.jsonl", repositorio))
        .isEqualTo(linhas("analysis.start.ok.jsonl").get(1));
  }

  @Test
  void analysisCancelDeIdDesconhecidoBateByteAByte() throws IOException {
    assertThat(despacharFixture("analysis.cancel.unknown.jsonl"))
        .isEqualTo(linhas("analysis.cancel.unknown.jsonl").get(1));
  }

  @Test
  void analysisStatusDeIdDesconhecidoBateByteAByte() throws IOException {
    assertThat(despacharFixture("analysis.status.not-found.jsonl"))
        .isEqualTo(linhas("analysis.status.not-found.jsonl").get(1));
  }

  @Test
  void protocolInvalidPayloadBateByteAByte() throws IOException {
    assertThat(despacharFixture("protocol.invalid-payload.jsonl"))
        .isEqualTo(linhas("protocol.invalid-payload.jsonl").get(1));
  }

  private static final class EventosDaAnalise implements FrameSink {
    private final List<JsonNode> eventos = new ArrayList<>();
    private final CanonicalJson canonico = new CanonicalJson();
    private final ObjectMapper leitor = new ObjectMapper();

    @Override
    public synchronized void emit(Envelope envelope) throws IOException {
      eventos.add(leitor.readTree(canonico.toBytes(envelope)));
      notifyAll();
    }

    synchronized JsonNode terminal() throws InterruptedException {
      final long prazo = System.nanoTime() + ESPERA.toNanos();
      while (true) {
        for (final JsonNode evento : eventos) {
          final String nome = evento.path("event").asText();
          if (Set.of("analysis.completed", "analysis.cancelled", "analysis.failed")
              .contains(nome)) {
            return evento;
          }
        }
        final long falta = prazo - System.nanoTime();
        if (falta <= 0) {
          throw new AssertionError("a análise da fixture não terminou: " + eventos);
        }
        wait(Math.max(1, falta / 1_000_000));
      }
    }
  }

  @Test
  void analysisStatusCompletedBateByteAByte(@TempDir Path temp) throws Exception {
    final Path repositorio = temp.toRealPath().resolve("multi-module");
    final var commit = TestRepositories.reproduceCorpusFixture("multi-module", repositorio);
    assertThat(commit.name()).isEqualTo(TestRepositories.corpusCommitId("multi-module"));
    final EventosDaAnalise eventos = new EventosDaAnalise();
    final AnalysisRegistry registro = registroQueRoda(eventos);
    final Dispatcher despachante =
        Dispatcher.of(inbound, Handlers.all(() -> 0L, encerrar, registro));

    final StartResult partida =
        registro.start(
            repositorio.toString(),
            repositorio,
            new AnalysisConfiguration(false, HistoryScope.ALL, null, null, false));
    assertThat(partida).isInstanceOf(StartResult.Started.class);
    ((StartResult.Started) partida).launch().run();
    final JsonNode terminal = eventos.terminal();
    assertThat(terminal.path("event").asText())
        .as(terminal.toString())
        .isEqualTo("analysis.completed");

    final List<String> l = linhas("analysis.status.completed.jsonl");
    assertThat(l).hasSize(2);
    final Dispatcher.Outcome fora =
        despachante.dispatch(despachante.parse(l.get(0).getBytes(StandardCharsets.UTF_8)));
    assertThat(fora).isInstanceOf(Dispatcher.Outcome.Respond.class);
    assertThat(emitido(((Dispatcher.Outcome.Respond) fora).envelope())).isEqualTo(l.get(1));
  }

  @Test
  void todaFixtureOuEDirigidaOuEDeclaradaNaoDirigivel() throws IOException {
    final Set<String> uniao = new TreeSet<>(DIRIGIDOS);
    uniao.addAll(EXPECTADOS_NAO_DIRIGIVEIS.keySet());
    assertThat(uniao)
        .as("acrescentar uma fixture obriga a decidir de que lado ela cai")
        .isEqualTo(arquivos());
  }

  static List<String> puladasComHandler(
      Map<String, String> primeiraLinhaPorArquivo, Set<String> registrados) throws IOException {
    final ObjectMapper leitor = new ObjectMapper();
    final List<String> falhas = new ArrayList<>();
    for (final Map.Entry<String, String> fixture : primeiraLinhaPorArquivo.entrySet()) {
      final JsonNode primeira = leitor.readTree(fixture.getValue());
      final String metodo = primeira.path("method").asText(null);
      if ("request".equals(primeira.path("kind").asText()) && registrados.contains(metodo)) {
        falhas.add(fixture.getKey() + " é pulada mas pede " + metodo + ", que está implementado");
      }
    }
    return falhas;
  }

  @Test
  void nenhumaFixtureNaoDirigidaPedeUmMetodoRegistrado() throws IOException {
    final Map<String, String> puladas = new LinkedHashMap<>();
    for (final String arquivo : EXPECTADOS_NAO_DIRIGIVEIS.keySet()) {
      puladas.put(arquivo, linhas(arquivo).get(0));
    }

    assertThat(puladasComHandler(puladas, dispatcher.registeredMethods()))
        .as("uma fixture não pode ser pulada por um método já implementado")
        .isEmpty();
  }

  @Test
  void aRegraLeOMetodoDaRequisicaoENaoAHasteDoArquivo() throws IOException {
    final String arquivo = "protocol.invalid-payload.jsonl";
    final Map<String, String> emMemoria = Map.of(arquivo, linhas(arquivo).get(0));
    final Set<String> registrados = Set.of("analysis.cancel");

    assertThat(puladasComHandler(emMemoria, registrados))
        .containsExactly(arquivo + " é pulada mas pede analysis.cancel, que está implementado");
    final String haste = arquivo.substring(0, arquivo.length() - ".jsonl".length());
    assertThat(registrados.stream().anyMatch(m -> haste.equals(m) || haste.startsWith(m + ".")))
        .as("a regra antiga, pela haste, era vacuidade para esta fixture")
        .isFalse();
  }

  @Test
  void oDespachanteRegistraExatamenteOsSeisMetodosJaImplementados() {
    assertThat(dispatcher.registeredMethods())
        .containsExactlyInAnyOrder(
            "system.ping",
            "system.shutdown",
            "repository.describe",
            "analysis.start",
            "analysis.cancel",
            "analysis.status");
  }

  @Test
  void asConstantesDeProtocoloBatemComOsGoldens() throws IOException {
    assertThat(AnalysisConstants.PROTOCOL_VERSION).isEqualTo(1);
    final JsonNode ready = inbound.readTree(linhas("protocol.ready.mvp.jsonl").get(0));
    assertThat(ready.get("protocol").asInt()).isEqualTo(AnalysisConstants.PROTOCOL_VERSION);
    assertThat(ready.get("payload").get("capabilities")).isEmpty();
  }
}
