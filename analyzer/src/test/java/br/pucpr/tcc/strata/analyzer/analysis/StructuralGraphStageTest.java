package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.GraphCounters;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class StructuralGraphStageTest {

  private static final Duration PASSO = Duration.ofMillis(150);

  private static final List<String> CHAVES =
      List.of(
          "typeNodes",
          "typeEdges",
          "selfPairs",
          "externalPairs",
          "denylistedPairs",
          "packageNodes",
          "packageEdges",
          "typeCycles",
          "typesInCycles",
          "packageCycles",
          "packagesInCycles",
          "pagerankUnconverged",
          "betweennessSkipped");

  private static final String LINHAS_DE_METRICA =
      """
      SELECT e.element_id, e.kind, g.*
        FROM graph_metric g JOIN code_element e ON e.id = g.element_pk
       WHERE g.analysis_pk = ?
       ORDER BY e.element_id""";

  private static final String LINHAS_DE_ARESTA =
      """
      SELECT s.element_id AS source_id, t.element_id AS target_id, p.*
        FROM package_edge p
        JOIN code_element s ON s.id = p.source_pk
        JOIN code_element t ON t.id = p.target_pk
       WHERE p.analysis_pk = ?
       ORDER BY s.element_id, t.element_id""";

  private static final List<String> OCORRENCIAS =
      List.of(
          "imports",
          "extends",
          "implements",
          "calls",
          "field_type",
          "parameter_type",
          "return_type",
          "annotation_reference");

  private static final List<String> COLUNAS_DO_RETRATO =
      List.of(
          "granularity",
          "fan_in",
          "fan_out",
          "fan_in_resolved",
          "fan_out_resolved",
          "fan_out_external",
          "external_fan_in",
          "scc_id",
          "scc_size",
          "bridge_span",
          "component_id",
          "internal_cohesion",
          "betweenness",
          "is_entry_point",
          "entry_point_kind");

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dados;
  @TempDir Path repositorios;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private final List<AnalysisRegistry> registros = new ArrayList<>();

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void fechar() {
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

  private static StagePlan planoComOGrafo() {
    return Stages.production()
        .with(STRUCTURAL_GRAPH, new StructuralGraphStage(ExternalPackages.load()));
  }

  private AnalysisRegistry registro(StagePlan plano) throws IOException {
    final AnalysisRegistry registro =
        new AnalysisRegistry(
            RunFixtures.settings(dados, quadros, plano, ManualNanoClock.stepping(PASSO)));
    registros.add(registro);
    return registro;
  }

  private record Repositorio(Path caminho, String raiz) {}

  private Repositorio fixture(String nome) throws Exception {
    final Path caminho = repositorios.toRealPath().resolve(nome);
    final String commit = TestRepositories.reproduceCorpusFixture(nome, caminho).name();
    assertThat(commit)
        .as("a reprodução tem de dar o commit que o make-fixtures.sh gravou")
        .isEqualTo(TestRepositories.corpusCommitId(nome));
    return new Repositorio(caminho, commit);
  }

  private record Analise(
      AnalysisId id, Path banco, long chave, Frame terminal, Map<String, Frame> concluidos) {

    JsonNode grafo() {
      return concluidos.get(STRUCTURAL_GRAPH.name()).payload();
    }
  }

  private Analise analisar(
      AnalysisRegistry registro, Repositorio repositorio, AnalysisConfiguration configuracao)
      throws Exception {
    final AnalysisId id = RunFixtures.launch(registro, repositorio.caminho(), configuracao);
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    RunFixtures.awaitRunEnd(id);
    final Path banco = RunFixtures.database(dados, repositorio.caminho(), repositorio.raiz());
    final long chave = numero(RunFixtures.analysisRow(banco, id).orElseThrow().get("id"));
    final Map<String, Frame> concluidos = new LinkedHashMap<>();
    for (final Frame quadro : quadros.events(id, "analysis.stage.completed")) {
      assertThat(concluidos.put(quadro.stage(), quadro))
          .as("dois analysis.stage.completed para " + quadro.stage())
          .isNull();
    }
    return new Analise(id, banco, chave, terminal, concluidos);
  }

  private Analise analisarComOGrafo(Repositorio repositorio, AnalysisConfiguration configuracao)
      throws Exception {
    return analisar(registro(planoComOGrafo()), repositorio, configuracao);
  }

  private static void completou(Analise analise) {
    assertThat(analise.terminal().event())
        .as("a análise completa; terminal: " + analise.terminal().line())
        .isEqualTo("analysis.completed");
    assertThat(analise.grafo().path("status").asText()).isEqualTo("COMPLETED");
  }

  private static long numero(Object valor) {
    return ((Number) valor).longValue();
  }

  private static Map<String, Long> contadoresDoEvento(JsonNode payload) {
    final Map<String, Long> valores = new LinkedHashMap<>();
    payload
        .path("counters")
        .properties()
        .forEach(e -> valores.put(e.getKey(), e.getValue().asLong()));
    return valores;
  }

  private static Map<String, Long> contadores(long... valores) {
    assertThat(valores).hasSize(CHAVES.size());
    final Map<String, Long> esperado = new LinkedHashMap<>();
    for (int i = 0; i < valores.length; i++) {
      esperado.put(CHAVES.get(i), valores[i]);
    }
    return esperado;
  }

  private static void contadoresSao(Analise analise, Map<String, Long> esperados) throws Exception {
    final Map<String, Long> doEvento = contadoresDoEvento(analise.grafo());
    assertThat(doEvento.keySet())
        .as("as treze chaves, nem mais nem menos")
        .containsExactlyInAnyOrderElementsOf(CHAVES);
    assertThat(doEvento).isEqualTo(esperados);
    final Map<String, Object> linha =
        RunFixtures.stageRows(analise.banco(), analise.id()).get(STRUCTURAL_GRAPH.name());
    assertThat(linha.get("status")).isEqualTo("COMPLETED");
    assertThat(JSON.readTree((String) linha.get("counters_json")))
        .as("o counters_json gravado é o payload do evento")
        .isEqualTo(analise.grafo().path("counters"));
  }

  private static List<Map<String, Object>> metricas(Analise analise) throws Exception {
    return RunFixtures.rows(analise.banco(), LINHAS_DE_METRICA, analise.chave());
  }

  private static List<Map<String, Object>> arestas(Analise analise) throws Exception {
    return RunFixtures.rows(analise.banco(), LINHAS_DE_ARESTA, analise.chave());
  }

  private static String valor(Map<String, Object> linha, String coluna) {
    final Object valor = linha.get(coluna);
    return valor == null ? "NULL" : valor.toString();
  }

  private static String retrato(Map<String, Object> linha) {
    return "%s in=%s out=%s inR=%s outR=%s ext=%s extIn=%s scc=%s/%s bridge=%s comp=%s coh=%s btw=%s entry=%s/%s"
        .formatted(COLUNAS_DO_RETRATO.stream().map(coluna -> valor(linha, coluna)).toArray());
  }

  private static String tipo(
      int in, int out, int inR, int outR, int ext, int extIn, String scc, int bridge) {
    return "TYPE in=%d out=%d inR=%d outR=%d ext=%d extIn=%d scc=%s bridge=%d comp=NULL coh=NULL btw=NULL entry=NULL/NULL"
        .formatted(in, out, inR, outR, ext, extIn, scc, bridge);
  }

  private static String pacote(int in, int out, String scc, int comp, int coh, String btw) {
    return "PACKAGE in=%d out=%d inR=NULL outR=NULL ext=NULL extIn=NULL scc=%s bridge=NULL comp=%d coh=%d btw=%s entry=NULL/NULL"
        .formatted(in, out, scc, comp, coh, btw);
  }

  private static Map<String, String> retratos(List<Map<String, Object>> linhas) {
    final Map<String, String> porId = new LinkedHashMap<>();
    for (final Map<String, Object> linha : linhas) {
      porId.put((String) linha.get("element_id"), retrato(linha));
    }
    return porId;
  }

  private static void umaLinhaPorVerticeESomasUm(
      List<Map<String, Object>> linhas, Analise analise, long tipos, long pacotes)
      throws Exception {
    for (final Map<String, Object> linha : linhas) {
      assertThat(linha.get("granularity"))
          .as(linha.get("element_id") + ": a granularidade é o tipo do elemento")
          .isEqualTo(linha.get("kind"));
    }
    assertThat(linhas.stream().filter(l -> "TYPE".equals(l.get("granularity"))).count())
        .isEqualTo(tipos);
    assertThat(linhas.stream().filter(l -> "PACKAGE".equals(l.get("granularity"))).count())
        .isEqualTo(pacotes);
    assertThat(
            RunFixtures.count(
                analise.banco(),
                "SELECT count(*) FROM code_element WHERE analysis_pk = ? AND kind = 'TYPE'"
                    + " AND is_external = 0",
                analise.chave()))
        .as("um vértice por tipo interno: o externo nunca é vértice")
        .isEqualTo(tipos);
    for (final String granularidade : List.of("TYPE", "PACKAGE")) {
      for (final String coluna : List.of("pagerank", "pagerank_out")) {
        double soma = 0;
        for (final Map<String, Object> linha : linhas) {
          if (granularidade.equals(linha.get("granularity"))) {
            soma += ((Number) linha.get(coluna)).doubleValue();
          }
        }
        assertThat(soma).as(granularidade + " " + coluna + " soma 1").isCloseTo(1.0, within(1e-9));
      }
    }
  }

  private static void aDaoLeOQueEstaGravado(Analise analise, List<Map<String, Object>> linhas)
      throws Exception {
    final List<GraphMetricRow> cruas = new ArrayList<>();
    for (final Map<String, Object> linha : linhas) {
      cruas.add(comoMetrica(linha));
    }
    cruas.sort(Comparator.comparingLong(GraphMetricRow::elementPk));
    try (Connection conexao = RunFixtures.readOnly(analise.banco())) {
      assertThat(GraphMetricDao.list(conexao, analise.chave())).isEqualTo(cruas);
    }
  }

  private static Integer inteiro(Object valor) {
    return valor == null ? null : ((Number) valor).intValue();
  }

  private static GraphMetricRow comoMetrica(Map<String, Object> linha) {
    return new GraphMetricRow(
        numero(linha.get("element_pk")),
        ElementKind.valueOf((String) linha.get("granularity")),
        inteiro(linha.get("fan_in")),
        inteiro(linha.get("fan_out")),
        inteiro(linha.get("fan_in_resolved")),
        inteiro(linha.get("fan_out_resolved")),
        inteiro(linha.get("fan_out_external")),
        inteiro(linha.get("external_fan_in")),
        ((Number) linha.get("pagerank")).doubleValue(),
        ((Number) linha.get("pagerank_out")).doubleValue(),
        inteiro(linha.get("scc_id")),
        inteiro(linha.get("scc_size")),
        inteiro(linha.get("bridge_span")),
        inteiro(linha.get("component_id")),
        inteiro(linha.get("internal_cohesion")),
        linha.get("betweenness") == null
            ? null
            : ((Number) linha.get("betweenness")).doubleValue());
  }

  private static String retratoDeAresta(Map<String, Object> linha) {
    final StringBuilder texto =
        new StringBuilder("pairs=")
            .append(valor(linha, "type_pair_count"))
            .append(' ')
            .append(valor(linha, "resolution"));
    for (final String coluna : OCORRENCIAS) {
      texto.append(' ').append(coluna).append('=').append(valor(linha, coluna));
    }
    return texto.append(" total=").append(valor(linha, "occurrence_total")).toString();
  }

  private static Map<String, String> retratosDeAresta(List<Map<String, Object>> linhas) {
    final Map<String, String> porPar = new LinkedHashMap<>();
    for (final Map<String, Object> linha : linhas) {
      porPar.put(linha.get("source_id") + " -> " + linha.get("target_id"), retratoDeAresta(linha));
    }
    return porPar;
  }

  private static void aDaoLeAsArestasGravadas(Analise analise, List<Map<String, Object>> linhas)
      throws Exception {
    final List<PackageEdgeRow> cruas = new ArrayList<>();
    final DependencyType[] tipos = DependencyType.values();
    for (final Map<String, Object> linha : linhas) {
      final Map<DependencyType, Integer> presentes = new EnumMap<>(DependencyType.class);
      for (int i = 0; i < OCORRENCIAS.size(); i++) {
        final int vezes = inteiro(linha.get(OCORRENCIAS.get(i)));
        if (vezes > 0) {
          presentes.put(tipos[i], vezes);
        }
      }
      cruas.add(
          new PackageEdgeRow(
              numero(linha.get("source_pk")),
              numero(linha.get("target_pk")),
              inteiro(linha.get("type_pair_count")),
              inteiro(linha.get("occurrence_total")),
              ResolutionQuality.valueOf((String) linha.get("resolution")),
              presentes));
    }
    cruas.sort(
        Comparator.comparingLong(PackageEdgeRow::sourcePk)
            .thenComparingLong(PackageEdgeRow::targetPk));
    try (Connection conexao = RunFixtures.readOnly(analise.banco())) {
      assertThat(PackageEdgeDao.list(conexao, analise.chave())).isEqualTo(cruas);
    }
  }

  @Test
  void asTrezeChavesDosContadoresSaoAsDeDecisionsS4() {
    assertThat(GraphCounters.EMPTY.asMap().keySet()).containsExactlyElementsOf(CHAVES);
    assertThat(GraphCounters.EMPTY.asMap().values()).containsOnly(0L);
  }

  @Test
  void aProducaoAindaNaoLigaOEstagioDoGrafo() {
    assertThat(Stages.production().executor(STRUCTURAL_GRAPH)).isSameAs(SkippedStage.INSTANCE);
  }

  @Test
  void oCyclicCompletaComOsTrezeContadoresExatos() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("cyclic"), defaults());

    completou(analise);
    contadoresSao(analise, contadores(6, 6, 0, 0, 0, 4, 3, 2, 5, 1, 2, 0, 0));
  }

  @Test
  void asLinhasDeGraphMetricDoCyclicSaoAsDerivadasAMao() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("cyclic"), defaults());

    completou(analise);
    final List<Map<String, Object>> linhas = metricas(analise);
    final Map<String, String> esperado = new LinkedHashMap<>();
    esperado.put("pkg:com.x.app", pacote(0, 1, "NULL/NULL", 1, 0, "0.0"));
    esperado.put("pkg:com.x.billing", pacote(0, 0, "NULL/NULL", 2, 3, "0.0"));
    esperado.put("pkg:com.x.orders", pacote(2, 1, "1/2", 1, 0, "1.0"));
    esperado.put("pkg:com.x.orders.dto", pacote(1, 1, "1/2", 1, 0, "0.0"));
    esperado.put("type:com.x.app.Main", tipo(0, 1, 0, 1, 0, 0, "NULL/NULL", 1));
    esperado.put("type:com.x.billing.Invoice", tipo(1, 1, 1, 1, 0, 0, "1/3", 0));
    esperado.put("type:com.x.billing.Ledger", tipo(1, 1, 1, 1, 0, 0, "1/3", 0));
    esperado.put("type:com.x.billing.Posting", tipo(1, 1, 1, 1, 0, 0, "1/3", 0));
    esperado.put("type:com.x.orders.Order", tipo(2, 1, 2, 1, 0, 2, "2/2", 3));
    esperado.put("type:com.x.orders.dto.OrderDto", tipo(1, 1, 1, 1, 0, 1, "2/2", 2));
    assertThat(retratos(linhas)).containsExactlyEntriesOf(esperado);
    umaLinhaPorVerticeESomasUm(linhas, analise, 6, 4);
    aDaoLeOQueEstaGravado(analise, linhas);
  }

  @Test
  void osCiclosEACoesaoDoCyclicSaoOsDoGolden() throws Exception {
    final JsonNode ciclos =
        JSON.readTree(TestRepositories.corpus("cyclic").resolve("expected.json").toFile())
            .path("cycles");
    final Analise analise = analisarComOGrafo(fixture("cyclic"), defaults());

    completou(analise);
    final Map<String, String> scc = new TreeMap<>();
    final Map<String, String> coesao = new TreeMap<>();
    for (final Map<String, Object> linha : metricas(analise)) {
      scc.put(
          (String) linha.get("element_id"),
          valor(linha, "scc_id") + "/" + valor(linha, "scc_size"));
      if ("PACKAGE".equals(linha.get("granularity"))) {
        coesao.put((String) linha.get("element_id"), valor(linha, "internal_cohesion"));
      }
    }
    final Map<String, String> esperado = new TreeMap<>();
    numerarPeloMenorMembro(ciclos.path("types"), esperado);
    numerarPeloMenorMembro(ciclos.path("packages"), esperado);
    ciclos.path("notInAnyCycle").forEach(tipo -> esperado.put(tipo.asText(), "NULL/NULL"));
    final Map<String, String> coesaoEsperada = new TreeMap<>();
    ciclos
        .path("internalCohesion")
        .properties()
        .forEach(
            e -> {
              coesaoEsperada.put(e.getKey(), e.getValue().asText());
              esperado.putIfAbsent(e.getKey(), "NULL/NULL");
            });

    assertThat(esperado)
        .as("o golden cobre os 6 tipos e os 4 pacotes: a seção é exaustiva")
        .hasSize(10);
    assertThat(scc).isEqualTo(esperado);
    assertThat(coesao).isEqualTo(coesaoEsperada);
  }

  private static void numerarPeloMenorMembro(JsonNode ciclos, Map<String, String> destino) {
    final List<List<String>> membros = new ArrayList<>();
    ciclos.forEach(
        ciclo -> {
          final List<String> lista = new ArrayList<>();
          ciclo.forEach(membro -> lista.add(membro.asText()));
          lista.sort(Comparator.naturalOrder());
          membros.add(lista);
        });
    membros.sort(Comparator.comparing(lista -> lista.get(0)));
    for (int i = 0; i < membros.size(); i++) {
      for (final String membro : membros.get(i)) {
        destino.put(membro, (i + 1) + "/" + membros.get(i).size());
      }
    }
  }

  @Test
  void oPageRankDoCyclicVaiParaAColunaCertaEmCadaGranularidade() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("cyclic"), defaults());

    completou(analise);
    final Map<String, double[]> esperado = new LinkedHashMap<>();
    esperado.put("type:com.x.app.Main", new double[] {1.0 / 40, 19.0 / 233});
    esperado.put("type:com.x.billing.Invoice", new double[] {1.0 / 6, 511.0 / 2097});
    esperado.put("type:com.x.billing.Ledger", new double[] {1.0 / 6, 511.0 / 2097});
    esperado.put("type:com.x.billing.Posting", new double[] {1.0 / 6, 511.0 / 2097});
    esperado.put("type:com.x.orders.Order", new double[] {9.0 / 37, 74.0 / 699});
    esperado.put("type:com.x.orders.dto.OrderDto", new double[] {343.0 / 1480, 19.0 / 233});
    esperado.put("pkg:com.x.app", new double[] {1.0 / 21, 1140.0 / 4271});
    esperado.put("pkg:com.x.billing", new double[] {1.0 / 21, 511.0 / 4271});
    esperado.put("pkg:com.x.orders", new double[] {120.0 / 259, 1480.0 / 4271});
    esperado.put("pkg:com.x.orders.dto", new double[] {49.0 / 111, 1140.0 / 4271});
    final double d = AnalysisConstants.PAGERANK_DAMPING;
    final double tolTipos = d / (1 - d) * 6 * AnalysisConstants.PAGERANK_TOLERANCE;
    final double tolPacotes = d / (1 - d) * 4 * AnalysisConstants.PAGERANK_TOLERANCE;

    final List<Map<String, Object>> linhas = metricas(analise);
    assertThat(linhas).hasSize(esperado.size());
    for (final Map<String, Object> linha : linhas) {
      final String id = (String) linha.get("element_id");
      final double tolerancia = id.startsWith("type:") ? tolTipos : tolPacotes;
      assertThat(((Number) linha.get("pagerank")).doubleValue())
          .as(id + ": pagerank é o grafo como extraído")
          .isCloseTo(esperado.get(id)[0], within(tolerancia));
      assertThat(((Number) linha.get("pagerank_out")).doubleValue())
          .as(id + ": pagerank_out é o grafo invertido")
          .isCloseTo(esperado.get(id)[1], within(tolerancia));
    }
  }

  @Test
  void asArestasDePacoteDoCyclicSaoAsTresDerivadasDoGolden() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("cyclic"), defaults());

    completou(analise);
    final List<Map<String, Object>> linhas = arestas(analise);
    final Map<String, String> esperado = new LinkedHashMap<>();
    esperado.put(
        "pkg:com.x.app -> pkg:com.x.orders",
        "pairs=1 RESOLVED imports=1 extends=0 implements=0 calls=1 field_type=1 parameter_type=1"
            + " return_type=0 annotation_reference=0 total=4");
    esperado.put(
        "pkg:com.x.orders -> pkg:com.x.orders.dto",
        "pairs=1 RESOLVED imports=1 extends=0 implements=0 calls=0 field_type=1 parameter_type=1"
            + " return_type=0 annotation_reference=0 total=3");
    esperado.put(
        "pkg:com.x.orders.dto -> pkg:com.x.orders",
        "pairs=1 RESOLVED imports=1 extends=0 implements=0 calls=0 field_type=1 parameter_type=1"
            + " return_type=0 annotation_reference=0 total=3");
    assertThat(retratosDeAresta(linhas)).containsExactlyEntriesOf(esperado);
    aDaoLeAsArestasGravadas(analise, linhas);
  }

  private static String t(String nome) {
    return "type:br.pucpr.legacy." + nome;
  }

  private static String p(String nome) {
    return "pkg:br.pucpr.legacy." + nome;
  }

  private static int forca(String qualidade) {
    return switch (qualidade) {
      case "RESOLVED" -> 2;
      case "INFERRED" -> 1;
      default -> 0;
    };
  }

  private static Map<String, String> paresDasLinhas(Analise analise) throws Exception {
    final Map<String, String> pares = new TreeMap<>();
    for (final Map<String, Object> linha :
        RunFixtures.rows(
            analise.banco(),
            "SELECT s.element_id AS origem, t.element_id AS destino, t.is_external AS externo,"
                + " d.resolution AS qualidade FROM dependency d"
                + " JOIN code_element s ON s.id = d.source_pk"
                + " JOIN code_element t ON t.id = d.target_pk WHERE d.analysis_pk = ?",
            analise.chave())) {
      final String origem = (String) linha.get("origem");
      final String destino = (String) linha.get("destino");
      final String chave = origem + " -> " + destino;
      if (origem.equals(destino)) {
        pares.put(chave, "próprio");
      } else if (numero(linha.get("externo")) == 1) {
        pares.put(chave, "externo");
      } else {
        final String qualidade = (String) linha.get("qualidade");
        pares.merge(chave, qualidade, (a, b) -> forca(a) <= forca(b) ? a : b);
      }
    }
    return pares;
  }

  private static Map<String, String> paresDoMultiModulePadrao() {
    final Map<String, String> pares = new TreeMap<>();
    pares.put(t("api.CatalogEndpoint") + " -> " + t("core.Catalog"), "INFERRED");
    pares.put(t("api.CatalogEndpoint") + " -> " + t("core.CatalogEntry"), "INFERRED");
    pares.put(t("api.CatalogEndpoint") + " -> " + t("core.Kind"), "INFERRED");
    pares.put(t("api.CatalogEndpoint") + " -> type:java.lang.String", "externo");
    pares.put(t("api.Main") + " -> " + t("api.CatalogEndpoint"), "RESOLVED");
    pares.put(t("api.Main") + " -> " + t("core.Catalog"), "INFERRED");
    pares.put(t("api.Main") + " -> " + t("core.spi.MemoryStore"), "INFERRED");
    pares.put(t("api.Main") + " -> type:java.lang.String", "externo");
    pares.put(t("core.Catalog") + " -> " + t("core.Audited"), "RESOLVED");
    pares.put(t("core.Catalog") + " -> " + t("core.CatalogEntry"), "RESOLVED");
    pares.put(t("core.Catalog") + " -> " + t("core.spi.Store"), "RESOLVED");
    pares.put(t("core.CatalogEntry") + " -> " + t("core.Kind"), "RESOLVED");
    pares.put(t("core.CatalogEntry") + " -> type:java.lang.String", "externo");
    pares.put(t("core.gen.CatalogMetadata") + " -> type:java.lang.String", "externo");
    pares.put(t("core.spi.MemoryStore") + " -> " + t("core.CatalogEntry"), "RESOLVED");
    pares.put(t("core.spi.MemoryStore") + " -> " + t("core.spi.Store"), "RESOLVED");
    pares.put(t("core.spi.Store") + " -> " + t("core.CatalogEntry"), "RESOLVED");
    return pares;
  }

  @Test
  void oMultiModuleCompletaComOsTrezeContadoresExatos() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("multi-module"), defaults());

    assertThat(paresDasLinhas(analise))
        .as("a premissa da derivação: as linhas que o SOURCE_ANALYSIS gravou")
        .isEqualTo(paresDoMultiModulePadrao());
    completou(analise);
    contadoresSao(analise, contadores(9, 13, 0, 0, 4, 4, 4, 0, 0, 1, 2, 0, 0));
  }

  @Test
  void oMultiModuleComTestesCompletaComOsTrezeContadoresExatos() throws Exception {
    final Analise analise =
        analisarComOGrafo(
            fixture("multi-module"),
            new AnalysisConfiguration(true, HistoryScope.ALL, null, null, false));

    final Map<String, String> premissa = new TreeMap<>(paresDoMultiModulePadrao());
    premissa.put(t("core.CatalogTest") + " -> " + t("core.Catalog"), "INFERRED");
    premissa.put(t("core.CatalogTest") + " -> " + t("core.CatalogEntry"), "INFERRED");
    premissa.put(t("core.CatalogTest") + " -> " + t("core.spi.MemoryStore"), "INFERRED");
    assertThat(paresDasLinhas(analise))
        .as("a premissa da derivação: as linhas que o SOURCE_ANALYSIS gravou")
        .isEqualTo(premissa);
    completou(analise);
    contadoresSao(analise, contadores(10, 16, 0, 0, 4, 4, 4, 0, 0, 1, 2, 0, 0));
    umaLinhaPorVerticeESomasUm(metricas(analise), analise, 10, 4);
  }

  @Test
  void asLinhasDeGraphMetricDoMultiModuleSaoAsDerivadasAMao() throws Exception {
    final Analise analise = analisarComOGrafo(fixture("multi-module"), defaults());

    assertThat(paresDasLinhas(analise)).isEqualTo(paresDoMultiModulePadrao());
    completou(analise);
    final List<Map<String, Object>> linhas = metricas(analise);
    final Map<String, String> esperado = new LinkedHashMap<>();
    esperado.put(p("api"), pacote(0, 2, "NULL/NULL", 1, 1, "0.0"));
    esperado.put(p("core"), pacote(2, 1, "1/2", 1, 3, "0.0"));
    esperado.put(p("core.gen"), pacote(0, 0, "NULL/NULL", 2, 0, "0.0"));
    esperado.put(p("core.spi"), pacote(2, 1, "1/2", 1, 1, "0.0"));
    esperado.put(t("api.CatalogEndpoint"), tipo(1, 3, 1, 0, 0, 0, "NULL/NULL", 1));
    esperado.put(t("api.Main"), tipo(0, 3, 0, 1, 0, 0, "NULL/NULL", 2));
    esperado.put(t("core.Audited"), tipo(1, 0, 1, 0, 0, 0, "NULL/NULL", 0));
    esperado.put(t("core.Catalog"), tipo(2, 3, 0, 3, 0, 2, "NULL/NULL", 2));
    esperado.put(t("core.CatalogEntry"), tipo(4, 1, 3, 1, 0, 3, "NULL/NULL", 2));
    esperado.put(t("core.Kind"), tipo(2, 0, 1, 0, 0, 1, "NULL/NULL", 1));
    esperado.put(t("core.gen.CatalogMetadata"), tipo(0, 0, 0, 0, 0, 0, "NULL/NULL", 0));
    esperado.put(t("core.spi.MemoryStore"), tipo(1, 2, 0, 2, 0, 1, "NULL/NULL", 2));
    esperado.put(t("core.spi.Store"), tipo(2, 1, 2, 1, 0, 1, "NULL/NULL", 2));
    assertThat(retratos(linhas)).containsExactlyEntriesOf(esperado);
    umaLinhaPorVerticeESomasUm(linhas, analise, 9, 4);
    aDaoLeOQueEstaGravado(analise, linhas);
  }

  @Test
  void asArestasDePacoteDoMultiModuleTemOsParesDaPremissaEAsOcorrenciasDasLinhas()
      throws Exception {
    final Analise analise = analisarComOGrafo(fixture("multi-module"), defaults());

    assertThat(paresDasLinhas(analise)).isEqualTo(paresDoMultiModulePadrao());
    completou(analise);
    final Map<String, String> ocorrencias = ocorrenciasPorAresta(analise);
    final Map<String, String> esperado = new LinkedHashMap<>();
    esperado.put(p("api") + " -> " + p("core"), "pairs=4 INFERRED " + ocorrencias.get("api>core"));
    esperado.put(
        p("api") + " -> " + p("core.spi"), "pairs=1 INFERRED " + ocorrencias.get("api>core.spi"));
    esperado.put(
        p("core") + " -> " + p("core.spi"), "pairs=1 RESOLVED " + ocorrencias.get("core>core.spi"));
    esperado.put(
        p("core.spi") + " -> " + p("core"), "pairs=2 RESOLVED " + ocorrencias.get("core.spi>core"));
    assertThat(ocorrencias).as("o oráculo achou as quatro arestas e só elas").hasSize(4);
    final List<Map<String, Object>> linhas = arestas(analise);
    assertThat(retratosDeAresta(linhas)).containsExactlyEntriesOf(esperado);
    aDaoLeAsArestasGravadas(analise, linhas);
  }

  private static Map<String, String> ocorrenciasPorAresta(Analise analise) throws Exception {
    final Map<String, Map<String, Long>> somas = new TreeMap<>();
    for (final Map<String, Object> linha :
        RunFixtures.rows(
            analise.banco(),
            "SELECT sp.element_id AS origem, tp.element_id AS destino, lower(d.type) AS coluna,"
                + " sum(d.occurrences) AS vezes FROM dependency d"
                + " JOIN code_element s ON s.id = d.source_pk"
                + " JOIN code_element t ON t.id = d.target_pk"
                + " JOIN code_element sp ON sp.id = s.parent_pk"
                + " JOIN code_element tp ON tp.id = t.parent_pk"
                + " WHERE d.analysis_pk = ? AND s.is_external = 0 AND t.is_external = 0"
                + " AND sp.id <> tp.id GROUP BY sp.element_id, tp.element_id, d.type",
            analise.chave())) {
      final String chave =
          ((String) linha.get("origem")).substring(p("").length())
              + ">"
              + ((String) linha.get("destino")).substring(p("").length());
      somas
          .computeIfAbsent(chave, k -> new LinkedHashMap<>())
          .put((String) linha.get("coluna"), numero(linha.get("vezes")));
    }
    final Map<String, String> retratos = new TreeMap<>();
    somas.forEach(
        (chave, porColuna) -> {
          final StringBuilder texto = new StringBuilder();
          long total = 0;
          for (final String coluna : OCORRENCIAS) {
            final long vezes = porColuna.getOrDefault(coluna, 0L);
            total += vezes;
            texto.append(coluna).append('=').append(vezes).append(' ');
          }
          retratos.put(chave, texto.append("total=").append(total).toString());
        });
    return retratos;
  }

  private static Map<String, String> metricasBitABit(Analise analise) throws Exception {
    final Map<String, String> porId = new TreeMap<>();
    for (final Map<String, Object> linha : metricas(analise)) {
      porId.put((String) linha.get("element_id"), semChaves(linha, Set.of("element_pk")));
    }
    return porId;
  }

  private static Map<String, String> arestasBitABit(Analise analise) throws Exception {
    final Map<String, String> porPar = new TreeMap<>();
    for (final Map<String, Object> linha : arestas(analise)) {
      porPar.put(
          linha.get("source_id") + " -> " + linha.get("target_id"),
          semChaves(linha, Set.of("source_pk", "target_pk")));
    }
    return porPar;
  }

  private static String semChaves(Map<String, Object> linha, Set<String> chaves) {
    final StringBuilder texto = new StringBuilder();
    for (final Map.Entry<String, Object> coluna : linha.entrySet()) {
      if (chaves.contains(coluna.getKey()) || "analysis_pk".equals(coluna.getKey())) {
        continue;
      }
      final Object valor = coluna.getValue();
      texto.append(coluna.getKey()).append('=');
      if (valor instanceof Double real) {
        texto.append("0x").append(Long.toHexString(Double.doubleToRawLongBits(real)));
      } else {
        texto.append(valor);
      }
      texto.append(' ');
    }
    return texto.toString();
  }

  private static Set<Long> chavesDeElemento(Analise analise) throws Exception {
    final Set<Long> chaves = new HashSet<>();
    for (final Map<String, Object> linha : metricas(analise)) {
      chaves.add(numero(linha.get("element_pk")));
    }
    return chaves;
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"multi-module, 13, 4", "cyclic, 10, 3"})
  void duasAnalisesDoMesmoRepositorioGravamAsMesmasLinhasBitABit(
      String nome, int vertices, int arestasDePacote) throws Exception {
    final Repositorio repositorio = fixture(nome);
    final AnalysisRegistry registro = registro(planoComOGrafo());

    final Analise primeira = analisar(registro, repositorio, defaults());
    final Analise segunda = analisar(registro, repositorio, defaults());

    completou(primeira);
    completou(segunda);
    assertThat(segunda.chave()).isNotEqualTo(primeira.chave());
    final Map<String, String> metricasDaPrimeira = metricasBitABit(primeira);
    assertThat(metricasDaPrimeira).hasSize(vertices);
    assertThat(metricasBitABit(segunda)).isEqualTo(metricasDaPrimeira);
    final Map<String, String> arestasDaPrimeira = arestasBitABit(primeira);
    assertThat(arestasDaPrimeira).hasSize(arestasDePacote);
    assertThat(arestasBitABit(segunda)).isEqualTo(arestasDaPrimeira);
    assertThat(chavesDeElemento(segunda))
        .as("as chaves das duas análises são outras: comparar por element_pk não provaria nada")
        .doesNotContainAnyElementsOf(chavesDeElemento(primeira));
  }

  @Test
  void umCancelamentoDentroDoEstagioNaoDeixaLinhaDoGrafo() throws Exception {
    final Repositorio repositorio = fixture("multi-module");
    final AnalysisRegistry registro = registro(planoComOGrafo());
    final AtomicBoolean cancelou = new AtomicBoolean();
    quadros.observe(
        quadro -> {
          final boolean passoDoGrafo =
              "analysis.progress".equals(quadro.event())
                  && STRUCTURAL_GRAPH.name().equals(quadro.stage());
          if (passoDoGrafo && cancelou.compareAndSet(false, true)) {
            final AnalysisRegistry.CancelResult pedido = registro.cancel(RunFixtures.FIXTURE_ID);
            assertThat(pedido.cancelled()).isTrue();
            pedido.afterResponse().run();
          }
        });

    final Analise analise = analisar(registro, repositorio, defaults());

    assertThat(analise.id()).isEqualTo(RunFixtures.FIXTURE_ID);
    assertThat(analise.terminal().event())
        .as("terminal: " + analise.terminal().line())
        .isEqualTo("analysis.cancelled");
    assertThat(cancelou)
        .as("anti-vacuidade: o primeiro passo do estágio saiu e o cancelamento veio nele")
        .isTrue();
    assertThat(analise.grafo().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(analise.grafo().path("counters").size())
        .as("nenhum contador de um estágio cancelado")
        .isZero();
    assertThat(RunFixtures.analysisRow(analise.banco(), analise.id()).orElseThrow().get("status"))
        .isEqualTo("CANCELLED");
    final Map<String, Map<String, Object>> etapas =
        RunFixtures.stageRows(analise.banco(), analise.id());
    assertThat(etapas.get(STRUCTURAL_GRAPH.name()).get("status")).isEqualTo("CANCELLED");
    for (final AnalysisStage depois :
        List.of(
            AnalysisStage.GIT_HISTORY_DETAILED,
            AnalysisStage.EVOLUTIONARY_COUPLING,
            AnalysisStage.HOTSPOT_PROFILES,
            AnalysisStage.GUIDED_TOUR,
            AnalysisStage.PERSISTENCE)) {
      assertThat(etapas.get(depois.name()).get("status")).as(depois.name()).isEqualTo("PENDING");
    }
    assertThat(
            RunFixtures.count(
                analise.banco(),
                "SELECT count(*) FROM graph_metric WHERE analysis_pk = ?",
                analise.chave()))
        .isZero();
    assertThat(
            RunFixtures.count(
                analise.banco(),
                "SELECT count(*) FROM package_edge WHERE analysis_pk = ?",
                analise.chave()))
        .isZero();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"package-info", "quebrado"})
  void umRepositorioSemTipoInternoCompletaComContadoresZeroESemLinha(String forma)
      throws Exception {
    final Path caminho = repositorios.toRealPath().resolve(forma);
    final String raiz;
    try (var git = TestRepositories.init(caminho)) {
      if ("package-info".equals(forma)) {
        TestRepositories.write(
            caminho, "src/main/java/a/package-info.java", "/** Só o pacote. */\npackage a;\n");
      } else {
        TestRepositories.write(
            caminho, "src/main/java/a/Quebrado.java", "package a;\nclass Quebrado {\n");
      }
      raiz = TestRepositories.commitAll(git, forma + "\n").name();
    }

    final Analise analise = analisarComOGrafo(new Repositorio(caminho, raiz), defaults());

    assertThat(
            RunFixtures.count(
                analise.banco(),
                "SELECT count(*) FROM code_element WHERE analysis_pk = ?"
                    + " AND kind IN ('TYPE', 'PACKAGE')",
                analise.chave()))
        .as("a premissa: nem tipo nem pacote no cache")
        .isZero();
    completou(analise);
    contadoresSao(analise, contadores(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThat(metricas(analise)).isEmpty();
    assertThat(arestas(analise)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"cyclic, 6", "multi-module, 9"})
  void oEstagioContaEmTiposDoZeroAoTotal(String nome, long tipos) throws Exception {
    final Analise analise = analisarComOGrafo(fixture(nome), defaults());

    completou(analise);
    final List<Frame> passos =
        quadros.events(analise.id(), "analysis.progress").stream()
            .filter(quadro -> STRUCTURAL_GRAPH.name().equals(quadro.stage()))
            .toList();
    assertThat(passos).as("o estágio conta").isNotEmpty();
    long anterior = -1;
    for (final Frame passo : passos) {
      assertThat(passo.payload().path("unit").asText()).as(passo.line()).isEqualTo("TYPES");
      assertThat(passo.payload().path("total").asLong()).as(passo.line()).isEqualTo(tipos);
      final long feitos = passo.payload().path("completed").asLong();
      assertThat(feitos).as("monotônico: " + passo.line()).isGreaterThanOrEqualTo(anterior);
      anterior = feitos;
    }
    assertThat(passos.get(0).payload().path("completed").asLong())
        .as("o primeiro passo é (0, typeNodes)")
        .isZero();
    final Frame terminal = passos.get(passos.size() - 1);
    assertThat(terminal.payload().path("completed").asLong())
        .as("o passo terminal é (typeNodes, typeNodes)")
        .isEqualTo(tipos);
    assertThat(terminal.index())
        .as("o passo terminal precede o stage.completed do estágio")
        .isLessThan(analise.concluidos().get(STRUCTURAL_GRAPH.name()).index());
  }
}
