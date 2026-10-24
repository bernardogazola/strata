package br.pucpr.tcc.strata.analyzer.protocol.method;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisConfiguration;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisId;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.analysis.ReadableAnalysis;
import br.pucpr.tcc.strata.analyzer.analysis.RegistrySettings;
import br.pucpr.tcc.strata.analyzer.analysis.StartResult;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MundoDeFixture implements AutoCloseable {

  static final AnalysisId ID = new AnalysisId("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  private static final Pattern POSIX_ABSOLUTO = Pattern.compile("^/");

  private static final Pattern UNIDADE_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

  private static final Pattern UNC = Pattern.compile("^\\\\\\\\");

  private static final Duration ESPERA = Duration.ofSeconds(120);

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private final String fixture;
  private final Path repositorio;
  private final List<String> raizesProibidas;
  private final AnalysisRegistry registro;
  private final Dispatcher despachante;
  private final Eventos eventos;
  private final ObjectMapper leitor = new ObjectMapper();
  private final CanonicalJson canonico = new CanonicalJson();
  private final AtomicInteger pedidos = new AtomicInteger();
  private final AtomicInteger stringsVarridas = new AtomicInteger();

  private MundoDeFixture(
      String fixture,
      Path repositorio,
      List<String> raizesProibidas,
      AnalysisRegistry registro,
      Eventos eventos) {
    this.fixture = fixture;
    this.repositorio = repositorio;
    this.raizesProibidas = List.copyOf(raizesProibidas);
    this.registro = registro;
    this.eventos = eventos;
    this.despachante =
        Dispatcher.of(
            JsonLines.strictInboundMapper(),
            Handlers.all(() -> 0L, new AtomicBoolean(false), registro));
  }

  static MundoDeFixture analisar(String fixture, Path base) throws Exception {
    final Path raiz = Files.createDirectories(base.resolve(fixture));
    final Path repositorio = raiz.toRealPath().resolve("repositorio");
    TestRepositories.reproduceCorpusFixture(fixture, repositorio);
    final Eventos eventos = new Eventos();
    final AnalysisRegistry registro =
        new AnalysisRegistry(
            RegistrySettings.production(
                    PersistenceRoot.prepare(Files.createDirectories(raiz.resolve("dados"))),
                    eventos,
                    "0.0.0-test")
                .withClock(InstantSource.fixed(TestRepositories.FIXTURE_IDENT.getWhenAsInstant()))
                .withNanoClock(() -> 0L)
                .withIds(() -> ID));
    try {
      final StartResult partida =
          registro.start(
              repositorio.toString(),
              repositorio,
              new AnalysisConfiguration(true, HistoryScope.ALL, null, null, false));
      assertThat(partida).as(fixture).isInstanceOf(StartResult.Started.class);
      ((StartResult.Started) partida).launch().run();
      final JsonNode terminal = eventos.terminal();
      assertThat(terminal.path("event").asText())
          .as(fixture + ": " + terminal)
          .isEqualTo("analysis.completed");
      final List<String> proibidas =
          List.of(
              base.toString(),
              base.toRealPath().toString(),
              repositorio.toString(),
              repositorio.toRealPath().toString());
      return new MundoDeFixture(fixture, repositorio, proibidas, registro, eventos);
    } catch (Exception | AssertionError falha) {
      registro.close();
      throw falha;
    }
  }

  @Override
  public void close() {
    registro.close();
  }

  String fixture() {
    return fixture;
  }

  Path repositorio() {
    return repositorio;
  }

  int stringsVarridas() {
    return stringsVarridas.get();
  }

  Set<String> arquivosJava() throws IOException {
    final Set<String> caminhos = new TreeSet<>();
    try (Stream<Path> arvore = Files.walk(repositorio)) {
      for (final Path arquivo : arvore.filter(Files::isRegularFile).toList()) {
        final Path relativo = repositorio.relativize(arquivo);
        final boolean noGit = ".git".equals(relativo.getName(0).toString());
        if (!noGit && relativo.getFileName().toString().endsWith(".java")) {
          caminhos.add(TestRepositories.posix(relativo));
        }
      }
    }
    return caminhos;
  }

  OraculoDoCache oraculo() throws SQLException {
    final ReadableAnalysis legivel = registro.readable(ID).orElseThrow();
    return legivel.connections().read(conexao -> OraculoDoCache.ler(conexao, legivel.analysisPk()));
  }

  List<JsonNode> eventos() {
    return eventos.todos();
  }

  JsonNode pedir(String metodo, ObjectNode payload) throws IOException {
    final ObjectNode corpo = leitor.createObjectNode().put("analysisId", ID.value());
    corpo.setAll(payload);
    final ObjectNode quadro = leitor.createObjectNode();
    quadro.put("id", novoId()).put("kind", "request").put("method", metodo);
    quadro.set("payload", corpo);
    quadro.put("protocol", 1);
    final Dispatcher.Outcome fora =
        despachante.dispatch(despachante.parse(leitor.writeValueAsBytes(quadro)));
    assertThat(fora).as(metodo).isInstanceOf(Dispatcher.Outcome.Respond.class);
    final Envelope envelope = ((Dispatcher.Outcome.Respond) fora).envelope();
    final String bytes = new String(canonico.toBytes(envelope), UTF_8);
    final JsonNode resposta = leitor.readTree(bytes);
    varrer(resposta, fixture + " " + metodo + " " + corpo);
    return resposta;
  }

  JsonNode ok(String metodo, ObjectNode payload) throws IOException {
    final JsonNode resposta = pedir(metodo, payload);
    assertThat(resposta.path("ok").asBoolean())
        .as(fixture + " " + metodo + " " + payload + " respondeu " + resposta)
        .isTrue();
    return resposta.path("payload");
  }

  void varrer(JsonNode quadro, String contexto) {
    if (quadro.isTextual()) {
      conferirString(quadro.textValue(), contexto);
    } else if (quadro.isObject()) {
      for (final Map.Entry<String, JsonNode> campo : quadro.properties()) {
        conferirString(campo.getKey(), contexto);
        varrer(campo.getValue(), contexto);
      }
    } else if (quadro.isArray()) {
      quadro.forEach(item -> varrer(item, contexto));
    }
  }

  private void conferirString(String texto, String contexto) {
    stringsVarridas.incrementAndGet();
    assertThat(POSIX_ABSOLUTO.matcher(texto).find())
        .as("%s: a string %s começa por /", contexto, texto)
        .isFalse();
    assertThat(UNIDADE_WINDOWS.matcher(texto).find())
        .as("%s: a string %s começa por uma unidade Windows", contexto, texto)
        .isFalse();
    assertThat(UNC.matcher(texto).find())
        .as("%s: a string %s começa por \\\\", contexto, texto)
        .isFalse();
    for (final String raiz : raizesProibidas) {
      assertThat(texto).as("%s: a raiz temporária no fio", contexto).doesNotContain(raiz);
    }
  }

  private String novoId() {
    final int n = pedidos.incrementAndGet();
    return "req_01JQ8ZK"
        + CROCKFORD.charAt(n / 1024 % 32)
        + CROCKFORD.charAt(n / 32 % 32)
        + CROCKFORD.charAt(n % 32)
        + "C3D4E5F6G7H8J9K0";
  }

  private static final class Eventos implements FrameSink {
    private final List<JsonNode> recebidos = new ArrayList<>();
    private final CanonicalJson canonico = new CanonicalJson();
    private final ObjectMapper leitor = new ObjectMapper();

    @Override
    public synchronized void emit(Envelope envelope) throws IOException {
      recebidos.add(leitor.readTree(canonico.toBytes(envelope)));
      notifyAll();
    }

    synchronized List<JsonNode> todos() {
      return List.copyOf(recebidos);
    }

    synchronized JsonNode terminal() throws InterruptedException {
      final long prazo = System.nanoTime() + ESPERA.toNanos();
      while (true) {
        for (final JsonNode evento : recebidos) {
          final String nome = evento.path("event").asText();
          if (Set.of("analysis.completed", "analysis.cancelled", "analysis.failed")
              .contains(nome)) {
            return evento;
          }
        }
        final long falta = prazo - System.nanoTime();
        if (falta <= 0) {
          throw new AssertionError("a análise não terminou: " + recebidos);
        }
        wait(Math.max(1, falta / 1_000_000));
      }
    }
  }
}
