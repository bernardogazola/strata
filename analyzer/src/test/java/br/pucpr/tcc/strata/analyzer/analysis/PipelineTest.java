package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.EVOLUTIONARY_COUPLING;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.GIT_HISTORY_CHEAP;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.GIT_HISTORY_DETAILED;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.GUIDED_TOUR;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.HOTSPOT_PROFILES;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.LAYOUT_DISCOVERY;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.PERSISTENCE;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.REPOSITORY_VALIDATION;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.SOURCE_ANALYSIS;
import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.FIXED_INSTANT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitDeath;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.analysis.RunSnapshot.StageSnapshot;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PipelineTest {

  private static final Duration RENDEZVOUS = Duration.ofSeconds(10);

  private static final Set<String> SOURCE_ANALYSIS_KEYS =
      Set.of(
          "files",
          "types",
          "resolvedRefs",
          "inferredRefs",
          "unresolvedRefs",
          "ambiguousRefs",
          "dropLocalTarget",
          "dropInvalidName",
          "dropIdTooLong",
          "dropDefaultPackageCollision",
          "dropCallWithoutTarget",
          "droppedDeclaredTargets",
          "unnumberedCallees",
          "duplicateElements",
          "elementIdsTooLong");

  private static final List<AnalysisStage> SKIPPED_IN_P27 =
      List.of(
          GIT_HISTORY_CHEAP,
          GIT_HISTORY_DETAILED,
          EVOLUTIONARY_COUPLING,
          HOTSPOT_PROFILES,
          GUIDED_TOUR);

  private static final Map<String, Long> STRUCTURAL_GRAPH_MULTI_MODULE =
      Map.ofEntries(
          Map.entry("betweennessSkipped", 0L),
          Map.entry("denylistedPairs", 4L),
          Map.entry("externalPairs", 0L),
          Map.entry("packageCycles", 1L),
          Map.entry("packageEdges", 4L),
          Map.entry("packageNodes", 4L),
          Map.entry("packagesInCycles", 2L),
          Map.entry("pagerankUnconverged", 0L),
          Map.entry("selfPairs", 0L),
          Map.entry("typeCycles", 0L),
          Map.entry("typeEdges", 13L),
          Map.entry("typeNodes", 9L),
          Map.entry("typesInCycles", 0L));

  private static final List<AnalysisStage> AFTER_THE_PAIR =
      List.of(
          STRUCTURAL_GRAPH,
          GIT_HISTORY_DETAILED,
          EVOLUTIONARY_COUPLING,
          HOTSPOT_PROFILES,
          GUIDED_TOUR,
          PERSISTENCE);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dados;
  @TempDir Path repositorios;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private final List<AnalysisRegistry> registros = new ArrayList<>();
  private final List<CountDownLatch> trincos = new ArrayList<>();

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void soltarEFechar() {
    trincos.forEach(CountDownLatch::countDown);
    RuntimeException primeira = null;
    for (final AnalysisRegistry registro : registros) {
      try {
        registro.close();
      } catch (RuntimeException falha) {
        if (primeira == null) {
          primeira = falha;
        } else {
          primeira.addSuppressed(falha);
        }
      }
    }
    if (primeira != null) {
      throw primeira;
    }
  }

  private AnalysisRegistry registro(StagePlan plano, LongSupplier nanos, int threads)
      throws IOException {
    final AnalysisRegistry registro =
        new AnalysisRegistry(
            RunFixtures.settings(dados, quadros, plano, nanos).withCpuPoolThreads(threads));
    registros.add(registro);
    return registro;
  }

  private AnalysisRegistry registro(StagePlan plano) throws IOException {
    return registro(plano, ManualNanoClock.frozen(), 2);
  }

  private CountDownLatch trinco() {
    final CountDownLatch trinco = new CountDownLatch(1);
    trincos.add(trinco);
    return trinco;
  }

  private static StagePlan com(StagePlan base, AnalysisStage stage, StageExecutor executor) {
    return base.with(stage, executor);
  }

  private record Marca(AnalysisStage stage, boolean inicio, String thread, boolean daemon) {

    static Marca de(AnalysisStage stage, boolean inicio) {
      final Thread atual = Thread.currentThread();
      return new Marca(stage, inicio, atual.getName(), atual.isDaemon());
    }
  }

  private static StageExecutor registrando(AnalysisStage stage, List<Marca> marcas) {
    return context -> {
      marcas.add(Marca.de(stage, true));
      marcas.add(Marca.de(stage, false));
      return StageOutcome.completed(StageCounters.none());
    };
  }

  private static StageExecutor encontro(
      AnalysisStage stage,
      AnalysisStage irmao,
      CountDownLatch meu,
      CountDownLatch doIrmao,
      List<Marca> marcas,
      List<String> falhas) {
    return context -> {
      marcas.add(Marca.de(stage, true));
      meu.countDown();
      if (!awaitLatch(doIrmao, RENDEZVOUS)) {
        falhas.add(
            stage
                + " esperou "
                + RENDEZVOUS.toSeconds()
                + " s por "
                + irmao
                + ": os dois não rodaram ao mesmo tempo");
      }
      marcas.add(Marca.de(stage, false));
      return StageOutcome.completed(StageCounters.none());
    };
  }

  private static int indice(List<Marca> marcas, AnalysisStage stage, boolean inicio) {
    for (int i = 0; i < marcas.size(); i++) {
      if (marcas.get(i).stage() == stage && marcas.get(i).inicio() == inicio) {
        return i;
      }
    }
    throw new AssertionError(stage + (inicio ? " nunca começou" : " nunca terminou"));
  }

  @ParameterizedTest(name = "cpuPoolThreads = {0}")
  @ValueSource(ints = {1, 8})
  void osDezEstagiosRodamEmOrdemEOParSeEncontraEmThreadsDiferentes(int threads) throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final List<Marca> marcas = new CopyOnWriteArrayList<>();
    final List<String> falhas = new CopyOnWriteArrayList<>();
    final CountDownLatch fonte = new CountDownLatch(1);
    final CountDownLatch historia = new CountDownLatch(1);
    final Map<AnalysisStage, StageExecutor> executores = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage stage : StagePlan.SPI_STAGES) {
      executores.put(stage, registrando(stage, marcas));
    }
    executores.put(
        SOURCE_ANALYSIS,
        encontro(SOURCE_ANALYSIS, GIT_HISTORY_CHEAP, fonte, historia, marcas, falhas));
    executores.put(
        GIT_HISTORY_CHEAP,
        encontro(GIT_HISTORY_CHEAP, SOURCE_ANALYSIS, historia, fonte, marcas, falhas));
    final AnalysisRegistry registro =
        registro(new StagePlan(executores), ManualNanoClock.frozen(), threads);

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);

    assertThat(falhas)
        .as("o par SOURCE_ANALYSIS ‖ GIT_HISTORY_CHEAP tem de se encontrar")
        .isEmpty();
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.completed");

    final List<AnalysisStage> inicios =
        marcas.stream().filter(Marca::inicio).map(Marca::stage).toList();
    assertThat(inicios).hasSize(8);
    assertThat(inicios.subList(0, 2)).containsExactlyInAnyOrder(SOURCE_ANALYSIS, GIT_HISTORY_CHEAP);
    assertThat(inicios.subList(2, 8)).containsExactlyElementsOf(AFTER_THE_PAIR);
    assertThat(
            Math.max(
                indice(marcas, SOURCE_ANALYSIS, true), indice(marcas, GIT_HISTORY_CHEAP, true)))
        .as("os dois membros começam antes de qualquer um terminar")
        .isLessThan(
            Math.min(
                indice(marcas, SOURCE_ANALYSIS, false), indice(marcas, GIT_HISTORY_CHEAP, false)));
    assertThat(indice(marcas, STRUCTURAL_GRAPH, true))
        .as("STRUCTURAL_GRAPH só começa depois de os dois membros terminarem")
        .isGreaterThan(
            Math.max(
                indice(marcas, SOURCE_ANALYSIS, false), indice(marcas, GIT_HISTORY_CHEAP, false)));
    assertThat(marcas.get(marcas.size() - 1)).isEqualTo(marcaFinal(marcas, PERSISTENCE));

    final Marca daFonte = marcas.get(indice(marcas, SOURCE_ANALYSIS, true));
    final Marca daHistoria = marcas.get(indice(marcas, GIT_HISTORY_CHEAP, true));
    assertThat(daFonte.thread())
        .as("SOURCE_ANALYSIS roda em linha, na thread da análise")
        .isEqualTo(RunFixtures.runThreadName(id));
    assertThat(daHistoria.thread())
        .as("o irmão roda no pool de CPU")
        .startsWith("strata-cpu-")
        .isNotEqualTo(daFonte.thread());
    assertThat(daFonte.daemon()).as("a thread da análise é daemon").isTrue();
    assertThat(daHistoria.daemon()).as("as threads do pool são daemon").isTrue();
    for (final AnalysisStage stage : AFTER_THE_PAIR) {
      assertThat(marcas.get(indice(marcas, stage, true)).thread())
          .as(stage + " é sequencial: roda na thread da análise")
          .isEqualTo(RunFixtures.runThreadName(id));
    }

    final List<String> concluidos =
        quadros.events(id, "analysis.stage.completed").stream().map(Frame::stage).toList();
    assertThat(concluidos).as("um analysis.stage.completed por estágio").hasSize(10);
    assertThat(concluidos.subList(0, 2))
        .containsExactly(REPOSITORY_VALIDATION.name(), LAYOUT_DISCOVERY.name());
    assertThat(concluidos.subList(2, 4))
        .containsExactlyInAnyOrder(SOURCE_ANALYSIS.name(), GIT_HISTORY_CHEAP.name());
    assertThat(concluidos.subList(4, 10))
        .containsExactlyElementsOf(AFTER_THE_PAIR.stream().map(Enum::name).toList());
    final List<Frame> daAnalise = quadros.of(id);
    assertThat(daAnalise.get(0).event()).isEqualTo("analysis.started");
    assertThat(daAnalise.get(daAnalise.size() - 1))
        .as("o evento terminal é o último quadro da análise")
        .isEqualTo(terminal);
  }

  private static Marca marcaFinal(List<Marca> marcas, AnalysisStage stage) {
    return marcas.get(indice(marcas, stage, false));
  }

  private static ValidatedRepository validado(Path repositorio) {
    final RepositoryOutcome<ValidatedRepository> desfecho =
        new RepositoryValidator().validate(repositorio);
    assertThat(desfecho).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) desfecho).value();
  }

  private static long somaDaSelecaoPadrao(ValidatedRepository repositorio) {
    return repositorio.layout().sourceRoots().stream()
        .filter(root -> root.kind() != SourceRootKind.TEST)
        .mapToLong(SourceRoot::javaFileCount)
        .sum();
  }

  private static long contarRaizes(ValidatedRepository repositorio, SourceRootKind kind) {
    return repositorio.layout().sourceRoots().stream().filter(root -> root.kind() == kind).count();
  }

  private static Map<String, Long> contadores(JsonNode payload) {
    final Map<String, Long> valores = new LinkedHashMap<>();
    payload
        .path("counters")
        .properties()
        .forEach(e -> valores.put(e.getKey(), e.getValue().asLong()));
    return valores;
  }

  private static Map<String, Frame> concluidosPorEstagio(List<Frame> concluidos) {
    final Map<String, Frame> porEstagio = new LinkedHashMap<>();
    for (final Frame quadro : concluidos) {
      assertThat(porEstagio.put(quadro.stage(), quadro))
          .as("dois analysis.stage.completed para " + quadro.stage())
          .isNull();
    }
    return porEstagio;
  }

  private static long numero(Object valor) {
    return ((Number) valor).longValue();
  }

  @Test
  void oPlanoDeProducaoAnalisaOMultiModuloEGravaExatamenteOQueEmitiu() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final String commit = RunFixtures.multiModuleCommit();
    final ValidatedRepository esperado = validado(repositorio);
    final long totalSelecionado = somaDaSelecaoPadrao(esperado);
    final AnalysisRegistry registro =
        registro(Stages.production(), ManualNanoClock.stepping(Duration.ofMillis(150)), 2);

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    final List<Frame> daAnalise = quadros.of(id);

    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.completed");
    assertThat(daAnalise.get(0).event()).isEqualTo("analysis.started");
    assertThat(daAnalise.get(daAnalise.size() - 1)).isEqualTo(terminal);

    final Map<String, Frame> concluido =
        concluidosPorEstagio(quadros.events(id, "analysis.stage.completed"));
    assertThat(concluido.keySet())
        .as("todo estágio rodou e emitiu um único analysis.stage.completed")
        .containsExactlyInAnyOrder(
            Arrays.stream(AnalysisStage.values()).map(Enum::name).toArray(String[]::new));

    final JsonNode fonte = concluido.get(SOURCE_ANALYSIS.name()).payload();
    assertThat(fonte.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(contadores(fonte).keySet())
        .as("os contadores do SOURCE_ANALYSIS são exatamente as chaves esperadas")
        .containsExactlyInAnyOrderElementsOf(SOURCE_ANALYSIS_KEYS);
    assertThat(contadores(fonte).get("files"))
        .as("files = completeness.totalFiles = Σ javaFileCount da seleção padrão")
        .isEqualTo(totalSelecionado);
    for (final AnalysisStage stage : SKIPPED_IN_P27) {
      final JsonNode pulado = concluido.get(stage.name()).payload();
      assertThat(pulado.path("status").asText()).as(stage.name()).isEqualTo("SKIPPED");
      assertThat(contadores(pulado)).as(stage + ": um SKIPPED não conta nada").isEmpty();
    }
    final JsonNode grafo = concluido.get(STRUCTURAL_GRAPH.name()).payload();
    assertThat(grafo.path("status").asText())
        .as("a produção liga o STRUCTURAL_GRAPH")
        .isEqualTo("COMPLETED");
    assertThat(contadores(grafo))
        .as("os treze contadores do grafo estrutural do multi-module")
        .isEqualTo(STRUCTURAL_GRAPH_MULTI_MODULE);
    final JsonNode persistencia = concluido.get(PERSISTENCE.name()).payload();
    assertThat(persistencia.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(contadores(persistencia)).isEmpty();
    assertThat(contadores(concluido.get(REPOSITORY_VALIDATION.name()).payload()))
        .as("REPOSITORY_VALIDATION {files, javaFiles, warnings}")
        .isEqualTo(
            Map.of(
                "files", (long) esperado.snapshot().inventory().totalFileCount(),
                "javaFiles", (long) esperado.snapshot().inventory().javaSources().size(),
                "warnings", 0L));
    assertThat(contadores(concluido.get(LAYOUT_DISCOVERY.name()).payload()))
        .as("LAYOUT_DISCOVERY {sourceRoots, mainRoots, generatedRoots, testRoots, selectedRoots}")
        .isEqualTo(
            Map.of(
                "sourceRoots", (long) esperado.layout().sourceRoots().size(),
                "mainRoots", contarRaizes(esperado, SourceRootKind.MAIN),
                "generatedRoots", contarRaizes(esperado, SourceRootKind.GENERATED),
                "testRoots", contarRaizes(esperado, SourceRootKind.TEST),
                "selectedRoots",
                    contarRaizes(esperado, SourceRootKind.MAIN)
                        + contarRaizes(esperado, SourceRootKind.GENERATED)));

    verificarPassos(daAnalise, concluido, esperado, totalSelecionado, terminal);

    final JsonNode completude = terminal.payload().path("completeness");
    assertThat(completude.path("totalFiles").asLong()).isEqualTo(totalSelecionado);
    assertThat(completude.path("shallowClone").asBoolean()).isFalse();
    assertThat(completude.path("historyTruncatedAtCommits").isNull()).isTrue();

    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status()).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(retrato.failure()).as("só um FAILED carrega erro").isNull();
    assertThat(retrato.startedAtMillis()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(retrato.finishedAtMillis()).isEqualTo(FIXED_INSTANT.toEpochMilli());
    assertThat(retrato.headCommitId()).isEqualTo(commit);
    final JsonNode completudeDoRetrato = JSON.valueToTree(retrato.completeness());
    assertThat(completudeDoRetrato).isEqualTo(completude);

    final Path banco = RunFixtures.database(dados, repositorio, commit);
    final Map<String, Object> analise = RunFixtures.analysisRow(banco, id).orElseThrow();
    assertThat(analise.get("status")).isEqualTo("COMPLETED");
    assertThat(numero(analise.get("started_at"))).isEqualTo(retrato.startedAtMillis());
    assertThat(numero(analise.get("finished_at"))).isEqualTo(retrato.finishedAtMillis());
    assertThat(analise.get("head_commit_id")).isEqualTo(commit);
    assertThat(analise.get("analyzer_version")).isEqualTo(RunFixtures.ANALYZER_VERSION);
    assertThat(analise.get("config_json"))
        .as("o JSON da configuração padrão")
        .isEqualTo(RunFixtures.DEFAULT_CONFIG_JSON);
    assertThat(analise.get("config_hash")).isEqualTo(RunFixtures.DEFAULT_CONFIG_HASH);
    assertThat(analise.get("source_fingerprint")).as("source_fingerprint fica nulo").isNull();
    assertThat(numero(analise.get("include_tests"))).isZero();
    assertThat(analise.get("history_scope")).isEqualTo("ALL");
    assertThat(analise.get("history_scope_commits")).isNull();
    assertThat(numero(analise.get("total_files"))).isEqualTo(totalSelecionado);
    assertThat(numero(analise.get("skipped_files")))
        .isEqualTo(retrato.completeness().skippedFiles());
    assertThat(numero(analise.get("parse_failures")))
        .isEqualTo(retrato.completeness().parseFailures());
    assertThat(numero(analise.get("shallow_clone"))).isZero();
    assertThat(analise.get("failure_code")).isNull();
    assertThat(analise.get("failure_detail")).isNull();

    final Map<String, Map<String, Object>> etapas = RunFixtures.stageRows(banco, id);
    assertThat(etapas).as("as dez linhas analysis_stage").hasSize(10);
    for (final AnalysisStage stage : AnalysisStage.values()) {
      final StageSnapshot daMemoria = retrato.stages().get(stage);
      final Map<String, Object> doBanco = etapas.get(stage.name());
      final JsonNode doFio = concluido.get(stage.name()).payload();
      assertThat(doBanco.get("status")).as(stage.name()).isEqualTo(daMemoria.status().name());
      assertThat(numero(doBanco.get("started_at")))
          .as(stage + ": started_at, também em um SKIPPED")
          .isEqualTo(daMemoria.startedAtMillis());
      assertThat(numero(doBanco.get("duration_ms")))
          .as(stage.name())
          .isEqualTo(daMemoria.durationMs());
      assertThat(numero(doBanco.get("duration_ms"))).isEqualTo(doFio.path("durationMs").asLong());
      assertThat(doBanco.get("counters_json"))
          .as(stage + ": o payload do evento é o counters_json gravado")
          .isEqualTo(daMemoria.counters().toJson());
      assertThat(JSON.readTree((String) doBanco.get("counters_json")))
          .isEqualTo(doFio.path("counters"));
      assertThat(doFio.path("status").asText()).isEqualTo(daMemoria.status().name());
    }

    assertThat(
            RunFixtures.count(
                banco,
                "SELECT count(*) FROM code_element e JOIN analysis a ON a.id = e.analysis_pk"
                    + " WHERE a.analysis_id = ? AND e.kind = 'TYPE' AND e.is_external = 0",
                id.value()))
        .as("types = os TYPE internos, is_external = 0")
        .isEqualTo(contadores(fonte).get("types"));

    final List<Map<String, Object>> repositorioGravado =
        RunFixtures.rows(banco, "SELECT * FROM repository");
    assertThat(repositorioGravado).as("a linha repository, primeiro chamador do upsert").hasSize(1);
    final Map<String, Object> linha = repositorioGravado.get(0);
    assertThat(linha.get("repository_key"))
        .isEqualTo(
            RepositoryIdentity.of(repositorio.toString(), PathFlavor.host(), commit).key().value());
    assertThat(linha.get("root_commit_id")).as("o commit raiz por primeiro pai").isEqualTo(commit);
    assertThat(linha.get("display_name")).isEqualTo("multi-module");
    assertThat(linha.get("default_branch")).isEqualTo("main");
    assertThat(numero(linha.get("is_shallow"))).isZero();
    assertThat(linha.get("build_system")).isEqualTo("MAVEN");
  }

  private static void verificarPassos(
      List<Frame> daAnalise,
      Map<String, Frame> concluido,
      ValidatedRepository esperado,
      long totalSelecionado,
      Frame terminal) {
    final Frame primeiroDepoisDoStarted = daAnalise.get(1);
    assertThat(primeiroDepoisDoStarted.event())
        .as("o passo (0, null) do REPOSITORY_VALIDATION é o primeiro ato depois do started")
        .isEqualTo("analysis.progress");
    assertThat(primeiroDepoisDoStarted.stage()).isEqualTo(REPOSITORY_VALIDATION.name());
    assertThat(primeiroDepoisDoStarted.payload().path("completed").asLong()).isZero();
    assertThat(primeiroDepoisDoStarted.payload().path("total").isNull()).isTrue();
    assertThat(primeiroDepoisDoStarted.payload().path("unit").asText()).isEqualTo("JAVA_FILES");

    final List<Frame> passos =
        daAnalise.stream().filter(frame -> "analysis.progress".equals(frame.event())).toList();
    assertThat(passos.stream().map(Frame::stage).distinct())
        .as(
            "só REPOSITORY_VALIDATION, SOURCE_ANALYSIS e STRUCTURAL_GRAPH"
                + " contam; LD, PERSISTENCE e os SKIPPED não")
        .containsExactlyInAnyOrder(
            REPOSITORY_VALIDATION.name(), SOURCE_ANALYSIS.name(), STRUCTURAL_GRAPH.name());
    for (final Frame passo : passos) {
      assertThat(passo.payload().path("unit").asText())
          .as(passo.stage() + ": a unidade do estágio")
          .isEqualTo(STRUCTURAL_GRAPH.name().equals(passo.stage()) ? "TYPES" : "JAVA_FILES");
      assertThat(passo.index()).isLessThan(terminal.index());
    }
    verificarPassosDoGrafo(passos, concluido);

    final long javaVistos = esperado.snapshot().inventory().javaSources().size();
    final List<Frame> daValidacao =
        passos.stream().filter(p -> REPOSITORY_VALIDATION.name().equals(p.stage())).toList();
    final Frame terminalDaValidacao = daValidacao.get(daValidacao.size() - 1);
    assertThat(terminalDaValidacao.payload().path("completed").asLong()).isEqualTo(javaVistos);
    assertThat(terminalDaValidacao.payload().path("total").asLong())
        .as("o passo terminal do RV é (javaFiles, javaFiles)")
        .isEqualTo(javaVistos);
    assertThat(terminalDaValidacao.index())
        .isLessThan(concluido.get(REPOSITORY_VALIDATION.name()).index());

    final List<Frame> daFonte =
        passos.stream().filter(p -> SOURCE_ANALYSIS.name().equals(p.stage())).toList();
    assertThat(daFonte).isNotEmpty();
    assertThat(daFonte.get(0).payload().path("completed").asLong())
        .as("o primeiro passo do SOURCE_ANALYSIS é (0, total)")
        .isZero();
    long anterior = -1;
    for (final Frame passo : daFonte) {
      final long feitos = passo.payload().path("completed").asLong();
      assertThat(passo.payload().path("total").asLong())
          .as("total = totalFiles, conhecido desde a seleção")
          .isEqualTo(totalSelecionado);
      assertThat(feitos).as("monotônico").isGreaterThanOrEqualTo(anterior);
      anterior = feitos;
    }
    final Frame ultimo = daFonte.get(daFonte.size() - 1);
    assertThat(ultimo.payload().path("completed").asLong())
        .as("o passo terminal é (total, total)")
        .isEqualTo(totalSelecionado);
    assertThat(ultimo.index())
        .as("o passo terminal precede o stage.completed do estágio")
        .isLessThan(concluido.get(SOURCE_ANALYSIS.name()).index());
  }

  private static void verificarPassosDoGrafo(List<Frame> passos, Map<String, Frame> concluido) {
    final long tipos = STRUCTURAL_GRAPH_MULTI_MODULE.get("typeNodes");
    final List<String> doGrafo =
        passos.stream()
            .filter(p -> STRUCTURAL_GRAPH.name().equals(p.stage()))
            .map(p -> p.payload().path("completed").asLong() + "/" + p.payload().path("total"))
            .toList();
    assertThat(doGrafo)
        .as("o STRUCTURAL_GRAPH conta (0, n) e (n, n) em TYPES")
        .containsExactly("0/" + tipos, tipos + "/" + tipos);
    final Frame ultimoDoGrafo =
        passos.stream()
            .filter(p -> STRUCTURAL_GRAPH.name().equals(p.stage()))
            .reduce((primeiro, segundo) -> segundo)
            .orElseThrow();
    assertThat(ultimoDoGrafo.index())
        .as("o passo terminal do grafo precede o stage.completed dele")
        .isLessThan(concluido.get(STRUCTURAL_GRAPH.name()).index());
  }

  @Test
  void umaAnaliseDeCadaVezEOMesmoRepositorioReusaOCacheAberto() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final Path outro = repositorios.toRealPath().resolve("outro");
    try (var git = TestRepositories.init(outro)) {
      TestRepositories.write(outro, "src/main/java/o/Outro.java", "package o;\nclass Outro {}\n");
      TestRepositories.commitAll(git, "outro\n");
    }
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch soltar = trinco();
    final AnalysisRegistry registro =
        registro(
            com(
                RunFixtures.lightPlan(),
                STRUCTURAL_GRAPH,
                context -> {
                  dentro.countDown();
                  awaitLatch(soltar, AWAIT);
                  return StageOutcome.skipped();
                }));

    final AnalysisId primeira = launch(registro, repositorio, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).as("a primeira análise chegou ao estágio preso").isTrue();

    assertThat(registro.start(repositorio.toString(), repositorio, defaults()))
        .isInstanceOf(StartResult.AlreadyRunning.class);
    assertThat(registro.start(outro.toString(), outro, defaults()))
        .as("o limite é um por processo, não por repositório")
        .isInstanceOf(StartResult.AlreadyRunning.class);
    assertThat(registro.status(primeira).orElseThrow().status()).isEqualTo(AnalysisStatus.RUNNING);

    soltar.countDown();
    assertThat(quadros.awaitTerminal(primeira, AWAIT).event()).isEqualTo("analysis.completed");
    awaitDeath(RunFixtures.runThread(quadros, primeira));

    final AnalysisId segunda = launch(registro, repositorio, defaults());
    assertThat(segunda).isNotEqualTo(primeira);
    final Frame terminal = quadros.awaitTerminal(segunda, AWAIT);
    assertThat(terminal.event())
        .as("a segunda análise reusa o cache aberto: nenhuma segunda abertura na mesma JVM")
        .isEqualTo("analysis.completed");

    final Path banco = RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit());
    assertThat(RunFixtures.rows(banco, "SELECT analysis_id, status FROM analysis ORDER BY id"))
        .containsExactly(
            Map.of("analysis_id", primeira.value(), "status", "COMPLETED"),
            Map.of("analysis_id", segunda.value(), "status", "COMPLETED"));
    final ReadableAnalysis lida1 = registro.readable(primeira).orElseThrow();
    final ReadableAnalysis lida2 = registro.readable(segunda).orElseThrow();
    assertThat(lida1.analysisPk()).isNotEqualTo(lida2.analysisPk());
    assertThat(lida1.connections())
        .as("o mesmo cache, aberto uma vez")
        .isSameAs(lida2.connections());
  }

  @Test
  void historyScopeCommitsAcimaDoTetoECortadoNaLinhaENoConfigJson() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AnalysisRegistry registro = registro(RunFixtures.lightPlan());
    final AnalysisConfiguration configuracao =
        AnalysisConfiguration.normalized(
            null, HistoryScope.LAST_N_COMMITS, 3_000_000_000L, null, null);

    final AnalysisId id = launch(registro, repositorio, configuracao);
    assertThat(quadros.awaitTerminal(id, AWAIT).event()).isEqualTo("analysis.completed");

    final String esperado =
        "{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":200000,"
            + "\"includeTests\":false,\"sourceRootIds\":null}";
    final Map<String, Object> analise =
        RunFixtures.analysisRow(
                RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()), id)
            .orElseThrow();
    assertThat(numero(analise.get("history_scope_commits"))).isEqualTo(200_000L);
    assertThat(analise.get("history_scope")).isEqualTo("LAST_N_COMMITS");
    assertThat(analise.get("config_json")).isEqualTo(esperado);
    assertThat(analise.get("config_hash")).isEqualTo(sha256(esperado));
  }

  private static String sha256(String texto) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(UTF_8)));
  }

  private static JsonNode comoJson(ProtocolError erro) {
    final JsonNode arvore = JSON.valueToTree(erro);
    return arvore;
  }

  private void verificarFalha(
      AnalysisRegistry registro,
      AnalysisId id,
      Path banco,
      ProtocolError erro,
      Map<AnalysisStage, StageStatus> estados)
      throws Exception {
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.failed");
    assertThat(terminal.payload().path("error"))
        .as("o erro da análise é o original")
        .isEqualTo(comoJson(erro));
    final List<Frame> daAnalise = quadros.of(id);
    assertThat(daAnalise.get(daAnalise.size() - 1)).isEqualTo(terminal);

    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(retrato.failure()).as("um FAILED carrega o erro").isEqualTo(erro);

    final Map<String, Frame> concluido =
        concluidosPorEstagio(quadros.events(id, "analysis.stage.completed"));
    final Map<String, Map<String, Object>> etapas = RunFixtures.stageRows(banco, id);
    for (final AnalysisStage stage : AnalysisStage.values()) {
      final StageStatus estado = estados.getOrDefault(stage, StageStatus.PENDING);
      assertThat(retrato.stages().get(stage).status()).as(stage.name()).isEqualTo(estado);
      assertThat(etapas.get(stage.name()).get("status")).as(stage.name()).isEqualTo(estado.name());
      if (estado == StageStatus.PENDING) {
        assertThat(concluido)
            .as(stage + " nunca começou: nenhum evento")
            .doesNotContainKey(stage.name());
        assertThat(etapas.get(stage.name()).get("started_at")).as(stage.name()).isNull();
      } else {
        assertThat(concluido.get(stage.name()).payload().path("status").asText())
            .as(stage + ": o evento leva o desfecho, FAILED e CANCELLED inclusive")
            .isEqualTo(estado.name());
      }
    }
    final Map<String, Object> analise = RunFixtures.analysisRow(banco, id).orElseThrow();
    assertThat(analise.get("status")).isEqualTo("FAILED");
    assertThat(analise.get("failure_code")).isEqualTo(erro.code());
    assertThat(analise.get("failure_detail")).isEqualTo(erro.detail());
  }

  private static Map<AnalysisStage, StageStatus> ateOPar(StageStatus fonte, StageStatus historia) {
    final Map<AnalysisStage, StageStatus> estados = new EnumMap<>(AnalysisStage.class);
    estados.put(REPOSITORY_VALIDATION, StageStatus.COMPLETED);
    estados.put(LAYOUT_DISCOVERY, StageStatus.COMPLETED);
    estados.put(SOURCE_ANALYSIS, fonte);
    estados.put(GIT_HISTORY_CHEAP, historia);
    return estados;
  }

  @Test
  void aFalhaDoIrmaoCancelaOSourceAnalysisEAAnaliseFalhaComOErroOriginal() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final ProtocolError erroDoIrmao =
        ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE);
    final CountDownLatch fonteRodando = new CountDownLatch(1);
    final StagePlan plano =
        com(
            com(
                RunFixtures.lightPlan(),
                SOURCE_ANALYSIS,
                RunFixtures.pollingUntilCancelled(fonteRodando, AWAIT)),
            GIT_HISTORY_CHEAP,
            context -> {
              awaitLatch(fonteRodando, AWAIT);
              throw new StageFailedException(erroDoIrmao);
            });
    final AnalysisRegistry registro = registro(plano);

    final AnalysisId id = launch(registro, repositorio, defaults());

    verificarFalha(
        registro,
        id,
        RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()),
        erroDoIrmao,
        ateOPar(StageStatus.CANCELLED, StageStatus.FAILED));
  }

  @Test
  void umaRecusaNoStructuralGraphFalhaAAnaliseEDeixaOsSeguintesPending() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final ProtocolError recusa = ErrorCodes.tooManyJavaSources(1, 2);
    final AnalysisRegistry registro =
        registro(
            com(
                RunFixtures.lightPlan(),
                STRUCTURAL_GRAPH,
                context -> {
                  throw new StageFailedException(recusa);
                }));

    final AnalysisId id = launch(registro, repositorio, defaults());

    final Map<AnalysisStage, StageStatus> estados =
        ateOPar(StageStatus.SKIPPED, StageStatus.SKIPPED);
    estados.put(STRUCTURAL_GRAPH, StageStatus.FAILED);
    verificarFalha(
        registro,
        id,
        RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()),
        recusa,
        estados);
  }

  private static final class ErroSintetico extends Error {
    private static final long serialVersionUID = 1L;

    ErroSintetico() {
      super("erro sintético do teste");
    }
  }

  @Test
  void umErrorFalhaAAnaliseComONomeDaClasseLiberaAVagaEChegaAoTratadorDaThread() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AtomicReference<Thread.UncaughtExceptionHandler> instalado = new AtomicReference<>();
    final AtomicReference<Throwable> capturado = new AtomicReference<>();
    final CountDownLatch chegou = new CountDownLatch(1);
    final AtomicBoolean jaLancou = new AtomicBoolean();
    final AnalysisRegistry registro =
        registro(
            com(
                RunFixtures.lightPlan(),
                STRUCTURAL_GRAPH,
                context -> {
                  if (!jaLancou.compareAndSet(false, true)) {
                    return StageOutcome.skipped();
                  }
                  final Thread atual = Thread.currentThread();
                  final Thread.UncaughtExceptionHandler original =
                      atual.getUncaughtExceptionHandler();
                  instalado.set(original);
                  atual.setUncaughtExceptionHandler(
                      (thread, erro) -> {
                        capturado.set(erro);
                        chegou.countDown();
                        original.uncaughtException(thread, erro);
                      });
                  throw new ErroSintetico();
                }));

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Thread daAnalise = RunFixtures.runThread(quadros, id);

    final ProtocolError esperado = ErrorCodes.internalUnexpected(ErroSintetico.class.getName());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.failed");
    assertThat(terminal.payload().path("error"))
        .as("FAILED INTERNAL_UNEXPECTED com o nome da classe, nunca a mensagem")
        .isEqualTo(comoJson(esperado));
    final List<Frame> daAnaliseQuadros = quadros.of(id);
    assertThat(daAnaliseQuadros.get(daAnaliseQuadros.size() - 1)).isEqualTo(terminal);
    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(retrato.failure()).isEqualTo(esperado);
    for (final AnalysisStage depois : AFTER_THE_PAIR.subList(1, AFTER_THE_PAIR.size())) {
      assertThat(retrato.stages().get(depois).status())
          .as(depois + " nunca começou")
          .isEqualTo(StageStatus.PENDING);
    }
    final Map<String, Object> analise =
        RunFixtures.analysisRow(
                RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()), id)
            .orElseThrow();
    assertThat(analise.get("status")).as("a linha, por melhor esforço").isEqualTo("FAILED");
    assertThat(analise.get("failure_code")).isEqualTo(esperado.code());
    assertThat(analise.get("failure_detail")).isEqualTo(esperado.detail());

    assertThat(awaitLatch(chegou, AWAIT))
        .as("o Error segue até o tratador da thread: a produção não o engole")
        .isTrue();
    assertThat(capturado.get()).isInstanceOf(ErroSintetico.class);
    assertThat(instalado.get())
        .as("a thread da análise tem tratador próprio, que loga")
        .isNotSameAs(daAnalise.getThreadGroup());
    awaitDeath(daAnalise);
    final AnalysisId depois = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(depois, AWAIT).event())
        .as("a vaga foi liberada")
        .isEqualTo("analysis.completed");
  }

  private void verificarRecusaDepoisDaResposta(
      AnalysisRegistry registro, AnalysisId id, Path banco, ProtocolError esperado)
      throws Exception {
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    final List<Frame> daAnalise = quadros.of(id);
    assertThat(daAnalise.get(0).event())
        .as("a recusa achada no disco é evento, depois do analysis.started")
        .isEqualTo("analysis.started");
    assertThat(terminal.event()).isEqualTo("analysis.failed");
    assertThat(daAnalise.get(daAnalise.size() - 1)).isEqualTo(terminal);
    if (esperado != null) {
      assertThat(terminal.payload().path("error")).isEqualTo(comoJson(esperado));
    }
    assertThat(registro.status(id).orElseThrow().status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(RunFixtures.analysisRow(banco, id))
        .as("uma análise que falha antes da ligação ao cache não tem linha")
        .isEmpty();
  }

  @Test
  void semJavaFalhaDepoisDoStartedComOsBytesDaFixture() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("sem-java");
    final String commit;
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(repositorio, "README.md", "# sem java\n");
      commit = TestRepositories.commitAll(git, "primeiro").name();
    }
    final AnalysisRegistry registro = registro(RunFixtures.lightPlan());

    final AnalysisId id = launch(registro, repositorio, defaults());
    assertThat(id).isEqualTo(RunFixtures.FIXTURE_ID);

    verificarRecusaDepoisDaResposta(
        registro, id, RunFixtures.database(dados, repositorio, commit), null);
    final String fixture =
        Files.readAllLines(fixtures().resolve("analysis.failed.no-java.jsonl"), UTF_8).get(0);
    assertThat(quadros.awaitTerminal(id, AWAIT).line()).isEqualTo(fixture);
    assertThat(registro.status(id).orElseThrow().stages().get(LAYOUT_DISCOVERY).status())
        .isEqualTo(StageStatus.FAILED);
  }

  @Test
  void umSourceRootIdDesconhecidoFalhaComoPayloadInvalidoSemEcoarOId() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final AnalysisRegistry registro = registro(RunFixtures.lightPlan());
    final AnalysisConfiguration configuracao =
        new AnalysisConfiguration(false, HistoryScope.ALL, null, List.of("sr_00000000"), false);

    final AnalysisId id = launch(registro, repositorio, configuracao);

    verificarRecusaDepoisDaResposta(
        registro,
        id,
        RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit()),
        null);
    verificarPayloadInvalido(quadros.awaitTerminal(id, AWAIT), "sr_00000000");
  }

  @Test
  void umaSelecaoSemArquivoFalhaComoPayloadInvalido() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("so-testes");
    final String commit;
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(
          repositorio, "src/test/java/t/SoTeste.java", "package t;\nclass SoTeste {}\n");
      commit = TestRepositories.commitAll(git, "so testes\n").name();
    }
    final AnalysisRegistry registro = registro(RunFixtures.lightPlan());

    final AnalysisId id = launch(registro, repositorio, defaults());

    verificarRecusaDepoisDaResposta(
        registro, id, RunFixtures.database(dados, repositorio, commit), null);
    verificarPayloadInvalido(quadros.awaitTerminal(id, AWAIT), "SoTeste");
  }

  private static void verificarPayloadInvalido(Frame terminal, String naoEcoa) {
    final JsonNode erro = terminal.payload().path("error");
    assertThat(erro.path("code").asText()).isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(erro.path("origin").asText()).isEqualTo(ErrorCodes.ORIGIN_ANALYZER);
    final JsonNode parametros = JSON.valueToTree(Map.of("method", "analysis.start"));
    assertThat(erro.path("params")).isEqualTo(parametros);
    assertThat(erro.path("detail").asText())
        .as("uma frase fixa em inglês, que nunca ecoa o valor")
        .isNotBlank()
        .doesNotContain(naoEcoa);
  }

  private static Path fixtures() {
    return Path.of(System.getProperty("strata.protocol.fixtures"));
  }

  private static String pedido(String id, String metodo, String payload, Path repositorio) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\"request\",\"method\":\""
        + metodo
        + "\",\"payload\":"
        + payload
        + ",\"protocol\":1"
        + (repositorio == null
            ? ""
            : ",\"repositoryPath\":" + JSON.valueToTree(repositorio.toString()))
        + "}";
  }

  @Test
  void noFioARespostaPrecedeOsEventosEOTerminalEOUltimo() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final Path fora = Files.createDirectories(repositorios.toRealPath().resolve("fora"));
    TestRepositories.symlink(repositorio.resolve("atalho"), fora);
    final CountDownLatch dentro = new CountDownLatch(1);
    final StagePlan plano =
        com(
            RunFixtures.lightPlan(),
            STRUCTURAL_GRAPH,
            RunFixtures.pollingUntilCancelled(dentro, AWAIT));
    final String start = "req_01JQ8ZE1B2C3D4E5F6G7H8J9K2";
    final String cancel = "req_01JQ8ZE2B2C3D4E5F6G7H8J9K2";

    try (WireHarness fio =
        new WireHarness(
            escritor -> {
              try {
                return RunFixtures.settings(dados, escritor, plano, ManualNanoClock.frozen());
              } catch (IOException falha) {
                throw new UncheckedIOException(falha);
              }
            })) {
      registros.add(fio.registry());
      fio.send(
          pedido(
              start,
              "analysis.start",
              "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":false,"
                  + "\"rebuildCache\":false,\"sourceRootIds\":null}",
              repositorio));
      final int respostaDoStart =
          fio.awaitLine(n -> start.equals(n.path("id").asText()), AWAIT, "a resposta do start");
      final JsonNode resposta = WireHarness.parse(fio.lines().get(respostaDoStart));
      assertThat(resposta.path("ok").asBoolean()).as(resposta.toString()).isTrue();
      final String id = resposta.path("payload").path("analysisId").asText();
      final int started =
          fio.awaitLine(
              n -> "analysis.started".equals(n.path("event").asText()),
              AWAIT,
              "o analysis.started");
      assertThat(respostaDoStart).as("resposta(start) < analysis.started").isLessThan(started);
      assertThat(awaitLatch(dentro, AWAIT)).isTrue();

      fio.send(pedido(cancel, "analysis.cancel", "{\"analysisId\":\"" + id + "\"}", null));
      final int respostaDoCancel =
          fio.awaitLine(n -> cancel.equals(n.path("id").asText()), AWAIT, "a resposta do cancel");
      assertThat(WireHarness.parse(fio.lines().get(respostaDoCancel)).path("payload"))
          .isEqualTo(JSON.readTree("{\"cancelled\":true}"));
      final int cancelado =
          fio.awaitLine(
              n -> "analysis.cancelled".equals(n.path("event").asText()),
              AWAIT,
              "o analysis.cancelled");
      assertThat(respostaDoCancel)
          .as("{cancelled: true} < analysis.cancelled")
          .isLessThan(cancelado);

      fio.closeInput();
      assertThat(fio.awaitExit(AWAIT)).isZero();
      fio.registry().close();

      final List<String> linhas = fio.lines();
      final List<Integer> daAnalise = new ArrayList<>();
      final List<Integer> avisos = new ArrayList<>();
      for (int i = 0; i < linhas.size(); i++) {
        final JsonNode quadro = WireHarness.parse(linhas.get(i));
        if (id.equals(quadro.path("payload").path("analysisId").asText()) && quadro.has("event")) {
          daAnalise.add(i);
          if ("analysis.warning".equals(quadro.path("event").asText())) {
            avisos.add(i);
          }
        }
      }
      assertThat(daAnalise.get(0)).isEqualTo(started);
      assertThat(daAnalise.get(daAnalise.size() - 1))
          .as("nada da análise depois do evento terminal")
          .isEqualTo(cancelado);
      assertThat(avisos)
          .as("anti-vacuidade: o link que escapa da raiz dá um analysis.warning")
          .isNotEmpty()
          .allSatisfy(i -> assertThat(i).as("aviso depois do started").isGreaterThan(started));
    }
  }
}
