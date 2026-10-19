package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class DependencyGoldenTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<String> SERVIDAS =
      List.of("broken-source", "cyclic", "inheritance", "method-calls", "missing-classpath");

  private static final List<SourceRootKind> ORDEM_DE_VISITA =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final List<String> PARES_VALIDOS =
      List.of("RESOLVED 0", "INFERRED 0", "RESOLVED 1", "UNRESOLVED_EXTERNAL 1");

  private static final int SCHEMA_VERSION = 1;

  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final String LINHAS_GRAVADAS =
      """
      SELECT s.element_id, t.element_id, d.type, d.occurrences, d.resolution, t.is_external,
             d.ambiguous, d.weight,
             (SELECT count(*) FROM dependency_evidence e WHERE e.dependency_pk = d.id)
        FROM dependency d
        JOIN code_element s ON s.id = d.source_pk
        JOIN code_element t ON t.id = d.target_pk
       WHERE d.analysis_pk = ?""";

  private static final String EVIDENCIAS_GRAVADAS =
      """
      SELECT s.element_id, t.element_id, d.type, f.relative_path, e.line, m.element_id,
             tm.element_id, e.resolution
        FROM dependency_evidence e
        JOIN dependency d ON d.id = e.dependency_pk
        JOIN code_element s ON s.id = d.source_pk
        JOIN code_element t ON t.id = d.target_pk
        LEFT JOIN source_file f ON f.id = e.file_pk
        LEFT JOIN code_element m ON m.id = e.from_method_pk
        LEFT JOIN code_element tm ON tm.id = e.to_method_pk
       WHERE d.analysis_pk = ?""";

  private static final String PESO_POR_PAR =
      """
      SELECT s.element_id, t.element_id, sum(d.weight)
        FROM dependency d
        JOIN code_element s ON s.id = d.source_pk
        JOIN code_element t ON t.id = d.target_pk
       WHERE d.analysis_pk = ?
       GROUP BY d.source_pk, d.target_pk""";

  private static final String LINHAS_DE_MODULO_EM_DEPENDENCY =
      "SELECT count(*) FROM dependency WHERE analysis_pk = ? AND type = 'MODULE_DEPENDENCY'";

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  private record Linha(
      CodeElementId from,
      CodeElementId to,
      DependencyType type,
      int occurrences,
      ResolutionQuality resolution,
      boolean external,
      boolean ambiguous,
      int weight,
      int evidence) {

    String tuple() {
      return from.sortKey()
          + " -> "
          + to.sortKey()
          + " "
          + type
          + " x"
          + occurrences
          + " "
          + resolution
          + (external ? " external" : "")
          + (ambiguous ? " ambiguous" : "");
    }

    Dependency dependency() {
      return new Dependency(from, to, type, resolution, occurrences);
    }
  }

  private record Evidencia(
      CodeElementId from,
      CodeElementId to,
      DependencyType type,
      String file,
      int line,
      String fromMethod,
      String toMethod,
      ResolutionQuality resolution) {

    String text() {
      return from.sortKey()
          + " -> "
          + to.sortKey()
          + " "
          + type
          + " "
          + file
          + " L"
          + line
          + " "
          + (fromMethod == null ? "-" : fromMethod)
          + (toMethod == null ? "" : " toMethod=" + toMethod);
    }
  }

  @BeforeAll
  static void jgitHermetico() {
    HermeticSystemReader.install();
  }

  static Stream<String> servidas() {
    return SERVIDAS.stream();
  }

  @Test
  void cincoGoldensReivindicamDependenciasEOsCincoSaoServidos() throws IOException {
    final List<String> todas = fixturesComGolden();
    final List<String> comDependencias =
        todas.stream().filter(fixture -> reivindica(fixture, "dependencies")).toList();

    assertThat(todas).hasSize(12);
    assertThat(comDependencias)
        .containsExactly(
            "broken-source", "cyclic", "inheritance", "method-calls", "missing-classpath");
    assertThat(SERVIDAS).as("todo golden de dependências é servido").isEqualTo(comDependencias);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("servidas")
  void reproduzAsDependenciasDoGoldenAPartirDasLinhasGravadas(String fixture) throws Exception {
    final JsonNode golden = golden(fixture);
    final ValidatedRepository repository = reproduzir(fixture);
    final PersistenceRoot persistence = PersistenceRoot.prepare(temp.toRealPath().resolve("data"));

    final List<Linha> linhas;
    final List<Evidencia> evidencias;
    final Map<String, Integer> pesoPorPar;
    final int linhasDeModulo;
    try (RepositoryCache cache = abrir(persistence, repository)) {
      final long analysisPk = extrair(cache, repository);
      linhas = linhasGravadas(cache, analysisPk);
      evidencias = evidenciasGravadas(cache, analysisPk);
      pesoPorPar = pesoPorPar(cache, analysisPk);
      linhasDeModulo = contar(cache, LINHAS_DE_MODULO_EM_DEPENDENCY, analysisPk);
    }

    assertThat(linhas)
        .extracting(Linha::tuple)
        .as(
            fixture
                + ": dependencies (from, to, type, occurrences, resolution, external, ambiguous),"
                + " exato")
        .containsExactlyElementsOf(tuplasDoGolden(golden));
    assertThat(linhas)
        .extracting(linha -> linha.resolution() + " " + (linha.external() ? 1 : 0))
        .as(fixture + ": todo par (resolution, is_external) é um dos quatro válidos")
        .allMatch(PARES_VALIDOS::contains);
    assertThat(linhas)
        .filteredOn(Linha::ambiguous)
        .as(fixture + ": ambígua só com UNRESOLVED_EXTERNAL, externa e de nome nu")
        .allMatch(
            linha ->
                linha.resolution() == ResolutionQuality.UNRESOLVED_EXTERNAL
                    && linha.external()
                    && linha.to().sortKey().indexOf('.') < 0);
    assertThat(linhasDeModulo)
        .as(fixture + ": MODULE_DEPENDENCY nunca entra em dependency")
        .isZero();
    assertThat(linhas)
        .as(fixture + ": cada linha guarda min(occurrences, 20) evidências")
        .allMatch(
            linha ->
                linha.evidence()
                    == Math.min(linha.occurrences(), AnalysisConstants.DEPENDENCY_EVIDENCE_CAP));

    final List<DependencyEdge> arestas =
        DependencyAggregator.aggregate(linhas.stream().map(Linha::dependency).toList());
    final Map<String, Integer> pesoDasArestas = new LinkedHashMap<>();
    for (final DependencyEdge aresta : arestas) {
      pesoDasArestas.put(aresta.source().value() + " " + aresta.target().value(), aresta.weight());
    }
    assertThat(pesoPorPar)
        .as(fixture + ": SUM(dependency.weight) por par = o peso da aresta agregada")
        .isEqualTo(pesoDasArestas);
    if (reivindica(golden, "edges")) {
      assertThat(arestas)
          .extracting(DependencyGoldenTest::aresta)
          .as(fixture + ": edges = a agregação das linhas lidas de volta")
          .containsExactlyElementsOf(arestasDoGolden(golden));
    }

    assertThat(evidencias)
        .filteredOn(evidencia -> evidencia.toMethod() != null)
        .as(fixture + ": toMethod só em uma chamada, e é um método")
        .allMatch(
            evidencia ->
                evidencia.type() == DependencyType.CALLS
                    && evidencia.toMethod().startsWith("method:"));
    if (reivindica(golden, "evidence")) {
      assertThat(evidencias)
          .extracting(Evidencia::text)
          .as(fixture + ": evidence (file, line, fromMethod, toMethod), exata")
          .containsExactlyElementsOf(evidenciasDoGolden(golden));
    }
  }

  private static List<String> fixturesComGolden() throws IOException {
    try (Stream<Path> children = Files.list(TestRepositories.corpusRoot())) {
      return children
          .filter(child -> Files.isRegularFile(child.resolve("expected.json")))
          .map(child -> child.getFileName().toString())
          .sorted()
          .toList();
    }
  }

  private static JsonNode golden(String fixture) throws IOException {
    return JSON.readTree(TestRepositories.corpus(fixture).resolve("expected.json").toFile());
  }

  private static boolean reivindica(String fixture, String secao) {
    try {
      return reivindica(golden(fixture), secao);
    } catch (IOException unreadable) {
      throw new IllegalStateException("golden ilegível: " + fixture, unreadable);
    }
  }

  private static boolean reivindica(JsonNode golden, String secao) {
    for (final JsonNode claim : golden.get("claims")) {
      if (claim.asText().equals(secao)) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode secao(JsonNode golden, String nome) {
    final JsonNode secao = golden.get(nome);
    if (secao == null || !secao.isArray()) {
      throw new IllegalStateException("o golden nomeia '" + nome + "', mas a seção não existe");
    }
    return secao;
  }

  private static List<String> tuplasDoGolden(JsonNode golden) {
    final List<String> tuplas = new ArrayList<>();
    for (final JsonNode row : secao(golden, "dependencies")) {
      tuplas.add(
          semPrefixo(row.get("from"))
              + " -> "
              + semPrefixo(row.get("to"))
              + " "
              + row.get("type").asText()
              + " x"
              + row.get("occurrences").asInt()
              + " "
              + row.get("resolution").asText()
              + (row.path("external").asBoolean(false) ? " external" : "")
              + (row.path("ambiguous").asBoolean(false) ? " ambiguous" : ""));
    }
    return List.copyOf(tuplas);
  }

  private static List<String> arestasDoGolden(JsonNode golden) {
    final List<String> arestas = new ArrayList<>();
    for (final JsonNode edge : secao(golden, "edges")) {
      final List<String> tipos = new ArrayList<>();
      edge.get("types")
          .properties()
          .forEach(tipo -> tipos.add(tipo.getKey() + "=" + tipo.getValue().asInt()));
      arestas.add(
          semPrefixo(edge.get("from"))
              + " -> "
              + semPrefixo(edge.get("to"))
              + " {"
              + String.join(", ", tipos)
              + "} d"
              + edge.get("distinctTypeCount").asInt()
              + " t"
              + edge.get("occurrenceTotal").asInt()
              + " "
              + edge.get("quality").asText()
              + " w"
              + edge.get("weight").asInt());
    }
    return List.copyOf(arestas);
  }

  private static List<String> evidenciasDoGolden(JsonNode golden) {
    final List<String> evidencias = new ArrayList<>();
    for (final JsonNode row : secao(golden, "evidence")) {
      evidencias.add(
          semPrefixo(row.get("from"))
              + " -> "
              + semPrefixo(row.get("to"))
              + " "
              + row.get("type").asText()
              + " "
              + row.get("file").asText()
              + " L"
              + row.get("line").asInt()
              + " "
              + (row.has("fromMethod") ? row.get("fromMethod").asText() : "-")
              + (row.has("toMethod") ? " toMethod=" + row.get("toMethod").asText() : ""));
    }
    return List.copyOf(evidencias);
  }

  private static String semPrefixo(JsonNode typeId) {
    return new CodeElementId(typeId.asText()).sortKey();
  }

  private static String aresta(DependencyEdge edge) {
    return edge.source().sortKey()
        + " -> "
        + edge.target().sortKey()
        + " "
        + edge.types()
        + " d"
        + edge.distinctTypeCount()
        + " t"
        + edge.occurrenceTotal()
        + " "
        + edge.resolution()
        + " w"
        + edge.weight();
  }

  private ValidatedRepository reproduzir(String fixture) throws Exception {
    final Path directory = temp.toRealPath().resolve("repo").resolve(fixture);
    TestRepositories.reproduceCorpusFixture(fixture, directory);
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
  }

  private static List<SourceRoot> raizesNaOrdemDeVisita(ValidatedRepository repository) {
    return repository.layout().sourceRoots().stream()
        .sorted(Comparator.comparingInt(raiz -> ORDEM_DE_VISITA.indexOf(raiz.kind())))
        .toList();
  }

  private RepositoryCache abrir(PersistenceRoot persistence, ValidatedRepository repository)
      throws CacheUnavailableException {
    return RepositoryCache.open(
            persistence,
            TestCaches.identityV01(),
            repository.snapshot().root(),
            "0.0.0-test",
            clock)
        .cache();
  }

  private long extrair(RepositoryCache cache, ValidatedRepository repository) throws SQLException {
    final List<SourceRoot> raizes = raizesNaOrdemDeVisita(repository);
    final SqliteConnections connections = cache.connections();
    final long analysisPk =
        connections.write(
            c ->
                AnalysisDao.insertRunning(
                    c,
                    new NewAnalysis(
                        ANALYSIS_ID,
                        clock.millis(),
                        repository.snapshot().head().commitId(),
                        "0.0.0-test",
                        SCHEMA_VERSION,
                        AnalysisConstants.PROTOCOL_VERSION,
                        "0".repeat(64),
                        "{\"includeTests\":true}",
                        null,
                        true,
                        HistoryScope.ALL,
                        null)));
    final ElementWriter writer =
        new ElementWriter(
            connections, analysisPk, raizes, repository.snapshot().inventory().javaSources());
    new SpoonModelBuilder()
        .analyze(repository, raizes, new StructureExtractor(raizes, writer), () -> false);
    return analysisPk;
  }

  private static List<Linha> linhasGravadas(RepositoryCache cache, long analysisPk)
      throws SQLException {
    final List<Linha> linhas =
        cache
            .connections()
            .read(
                c -> {
                  final List<Linha> lidas = new ArrayList<>();
                  try (PreparedStatement statement = c.prepareStatement(LINHAS_GRAVADAS)) {
                    statement.setLong(1, analysisPk);
                    try (ResultSet rows = statement.executeQuery()) {
                      while (rows.next()) {
                        lidas.add(
                            new Linha(
                                new CodeElementId(rows.getString(1)),
                                new CodeElementId(rows.getString(2)),
                                DependencyType.valueOf(rows.getString(3)),
                                rows.getInt(4),
                                ResolutionQuality.valueOf(rows.getString(5)),
                                rows.getInt(6) == 1,
                                rows.getInt(7) == 1,
                                rows.getInt(8),
                                rows.getInt(9)));
                      }
                    }
                  }
                  return lidas;
                });
    return linhas.stream()
        .sorted(
            Comparator.comparing(Linha::from).thenComparing(Linha::to).thenComparing(Linha::type))
        .toList();
  }

  private static List<Evidencia> evidenciasGravadas(RepositoryCache cache, long analysisPk)
      throws SQLException {
    final List<Evidencia> evidencias =
        cache
            .connections()
            .read(
                c -> {
                  final List<Evidencia> lidas = new ArrayList<>();
                  try (PreparedStatement statement = c.prepareStatement(EVIDENCIAS_GRAVADAS)) {
                    statement.setLong(1, analysisPk);
                    try (ResultSet rows = statement.executeQuery()) {
                      while (rows.next()) {
                        lidas.add(
                            new Evidencia(
                                new CodeElementId(rows.getString(1)),
                                new CodeElementId(rows.getString(2)),
                                DependencyType.valueOf(rows.getString(3)),
                                rows.getString(4),
                                rows.getInt(5),
                                rows.getString(6),
                                rows.getString(7),
                                ResolutionQuality.valueOf(rows.getString(8))));
                      }
                    }
                  }
                  return lidas;
                });
    return evidencias.stream()
        .sorted(
            Comparator.comparing(Evidencia::from)
                .thenComparing(Evidencia::to)
                .thenComparing(Evidencia::type)
                .thenComparingInt(Evidencia::line))
        .toList();
  }

  private static Map<String, Integer> pesoPorPar(RepositoryCache cache, long analysisPk)
      throws SQLException {
    final Map<String, Integer> lidos =
        cache
            .connections()
            .read(
                c -> {
                  final Map<String, Integer> pesos = new LinkedHashMap<>();
                  try (PreparedStatement statement = c.prepareStatement(PESO_POR_PAR)) {
                    statement.setLong(1, analysisPk);
                    try (ResultSet rows = statement.executeQuery()) {
                      while (rows.next()) {
                        pesos.put(rows.getString(1) + " " + rows.getString(2), rows.getInt(3));
                      }
                    }
                  }
                  return pesos;
                });
    final Map<String, Integer> ordenados = new LinkedHashMap<>();
    lidos.keySet().stream()
        .sorted(DependencyGoldenTest::compararPares)
        .forEach(par -> ordenados.put(par, lidos.get(par)));
    return ordenados;
  }

  private static int compararPares(String left, String right) {
    final String[] a = left.split(" ");
    final String[] b = right.split(" ");
    final int bySource = CodeElementId.compareCodePoints(a[0], b[0]);
    return bySource != 0 ? bySource : CodeElementId.compareCodePoints(a[1], b[1]);
  }

  private static int contar(RepositoryCache cache, String sql, long analysisPk)
      throws SQLException {
    return cache
        .connections()
        .read(
            c -> {
              try (PreparedStatement statement = c.prepareStatement(sql)) {
                statement.setLong(1, analysisPk);
                try (ResultSet rows = statement.executeQuery()) {
                  return rows.next() ? rows.getInt(1) : 0;
                }
              }
            });
  }
}
