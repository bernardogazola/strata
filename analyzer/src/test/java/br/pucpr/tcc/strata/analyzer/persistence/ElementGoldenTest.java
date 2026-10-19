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
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ElementGoldenTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<String> SECOES_DE_ELEMENTOS =
      List.of("files", "methods", "packages", "types");

  private static final List<SourceRootKind> ORDEM_DE_VISITA =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final Path EVIDENCIA = Path.of("target", "code-elements");

  private static final int SCHEMA_VERSION = 1;

  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final String ELEMENTOS_GRAVADOS =
      """
      SELECT e.element_id, e.kind, e.type_kind, e.is_test, r.relative_path, r.kind
        FROM code_element e
        LEFT JOIN source_root r ON r.id = e.source_root_pk
       WHERE e.analysis_pk = ? AND e.is_external = 0
       ORDER BY e.element_id""";

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  private record Linha(
      String id, String kind, String typeKind, boolean test, String root, String rootKind) {}

  private record Tipo(String id, String kind, String root, Boolean test, Boolean generated) {

    @Override
    public String toString() {
      final StringBuilder texto =
          new StringBuilder("{\"id\": \"")
              .append(id)
              .append("\", \"kind\": \"")
              .append(kind)
              .append('"');
      if (root != null) {
        texto.append(", \"root\": \"").append(root).append('"');
      }
      if (test != null) {
        texto.append(", \"test\": ").append(test);
      }
      if (generated != null) {
        texto.append(", \"generated\": ").append(generated);
      }
      return texto.append('}').toString();
    }
  }

  @BeforeAll
  static void jgitHermetico() {
    HermeticSystemReader.install();
  }

  static Stream<String> fixturesComElementos() throws IOException {
    final List<String> fixtures = new ArrayList<>();
    for (final String fixture : fixturesComGolden()) {
      if (secoesReivindicadas(golden(fixture)).stream().anyMatch(SECOES_DE_ELEMENTOS::contains)) {
        fixtures.add(fixture);
      }
    }
    return fixtures.stream();
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

  private static List<String> secoesReivindicadas(JsonNode golden) {
    return textos(golden.get("claims"));
  }

  private static List<String> textos(JsonNode array) {
    final List<String> textos = new ArrayList<>();
    array.forEach(item -> textos.add(item.asText()));
    return List.copyOf(textos);
  }

  private static JsonNode secao(JsonNode golden, String nome) {
    final JsonNode secao = golden.get(nome);
    if (secao == null || !secao.isArray()) {
      throw new IllegalStateException("o golden nomeia '" + nome + "', mas a seção não existe");
    }
    return secao;
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
    return cache
        .connections()
        .read(
            c -> {
              final List<Linha> linhas = new ArrayList<>();
              try (PreparedStatement statement = c.prepareStatement(ELEMENTOS_GRAVADOS)) {
                statement.setLong(1, analysisPk);
                try (ResultSet rows = statement.executeQuery()) {
                  while (rows.next()) {
                    linhas.add(
                        new Linha(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getInt(4) == 1,
                            rows.getString(5),
                            rows.getString(6)));
                  }
                }
              }
              return List.copyOf(linhas);
            });
  }

  private static List<String> ids(List<Linha> linhas, String kind) {
    return linhas.stream().filter(linha -> kind.equals(linha.kind())).map(Linha::id).toList();
  }

  private static boolean algumUsa(JsonNode tipos, String chave) {
    for (final JsonNode tipo : tipos) {
      if (tipo.has(chave)) {
        return true;
      }
    }
    return false;
  }

  private static List<Tipo> tiposEsperados(JsonNode tipos) {
    final boolean usaTest = algumUsa(tipos, "test");
    final boolean usaGenerated = algumUsa(tipos, "generated");
    final List<Tipo> esperados = new ArrayList<>();
    for (final JsonNode tipo : tipos) {
      esperados.add(
          new Tipo(
              tipo.get("id").asText(),
              tipo.get("kind").asText(),
              tipo.has("root") ? tipo.get("root").asText() : null,
              usaTest ? tipo.path("test").asBoolean(false) : null,
              usaGenerated ? tipo.path("generated").asBoolean(false) : null));
    }
    return List.copyOf(esperados);
  }

  private static List<Tipo> tiposGravados(List<Linha> linhas, JsonNode tipos) {
    final boolean usaTest = algumUsa(tipos, "test");
    final boolean usaGenerated = algumUsa(tipos, "generated");
    final Map<String, Boolean> temRaiz = new HashMap<>();
    tipos.forEach(tipo -> temRaiz.put(tipo.get("id").asText(), tipo.has("root")));
    final List<Tipo> gravados = new ArrayList<>();
    for (final Linha linha : linhas) {
      if (!"TYPE".equals(linha.kind())) {
        continue;
      }
      gravados.add(
          new Tipo(
              linha.id(),
              linha.typeKind(),
              temRaiz.getOrDefault(linha.id(), false) ? linha.root() : null,
              usaTest ? linha.test() : null,
              usaGenerated ? SourceRootKind.GENERATED.name().equals(linha.rootKind()) : null));
    }
    return List.copyOf(gravados);
  }

  @Test
  void onzeDosDozeGoldensReivindicamElementosESoOMassChangeFicaDeFora() throws IOException {
    final List<String> todas = fixturesComGolden();
    final List<String> comElementos = fixturesComElementos().toList();

    assertThat(todas)
        .as("sem esta asserção o teste parametrizado passaria por vacuidade sobre lista vazia")
        .hasSize(12);
    assertThat(comElementos).hasSize(11);
    assertThat(todas)
        .as("uma fixture nova que não reivindica elementos tem de ser vista aqui, não ignorada")
        .filteredOn(fixture -> !comElementos.contains(fixture))
        .containsExactly("mass-change");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixturesComElementos")
  void reproduzOsElementosDoGoldenAPartirDasLinhasGravadas(String fixture) throws Exception {
    final JsonNode golden = golden(fixture);
    final List<String> claims = secoesReivindicadas(golden);
    final ValidatedRepository repository = reproduzir(fixture);
    final PersistenceRoot persistence = PersistenceRoot.prepare(temp.toRealPath().resolve("data"));

    final List<Linha> linhas;
    try (RepositoryCache cache = abrir(persistence, repository)) {
      linhas = linhasGravadas(cache, extrair(cache, repository));
    }

    if (claims.contains("packages")) {
      assertThat(ids(linhas, "PACKAGE"))
          .as(fixture + ": packages, na ordem por ponto de código")
          .containsExactlyElementsOf(textos(secao(golden, "packages")));
    }
    if (claims.contains("types")) {
      final JsonNode tipos = secao(golden, "types");
      assertThat(tiposGravados(linhas, tipos))
          .as(fixture + ": types (kind, root, test, generated), na ordem por ponto de código")
          .containsExactlyElementsOf(tiposEsperados(tipos));
    }
    if (claims.contains("files")) {
      assertThat(ids(linhas, "FILE"))
          .as(fixture + ": files, na ordem por ponto de código")
          .containsExactlyElementsOf(textos(secao(golden, "files")));
    }
    if (claims.contains("methods")) {
      assertThat(ids(linhas, "METHOD"))
          .as(fixture + ": methods, na ordem por ponto de código")
          .containsExactlyElementsOf(textos(secao(golden, "methods")));
    }
  }

  @Test
  void simplePackagesDeixaEmTargetOCacheQueOSqlite3DoPortaoLe() throws Exception {
    TestCaches.deleteTree(EVIDENCIA);
    final PersistenceRoot evidencia = PersistenceRoot.prepare(EVIDENCIA.toAbsolutePath());
    final ValidatedRepository repository = reproduzir("simple-packages");

    try (RepositoryCache cache = abrir(evidencia, repository)) {
      extrair(cache, repository);
    }

    final Path banco =
        evidencia.directory().resolve(TestCaches.V01_KEY).resolve(RepositoryCache.DATABASE_FILE);
    assertThat(TestCaches.sibling(banco, "-wal"))
        .as("fechado, o arquivo se basta: um sqlite3 externo não depende de um -wal ao lado")
        .doesNotExist();
    try (Connection raw = TestCaches.raw(banco)) {
      assertThat(TestCaches.scalar(raw, "select count(*) from analysis")).isEqualTo("1");
      assertThat(
              TestCaches.column(
                  raw,
                  "select kind || '|' || count(*) from code_element where is_external = 0"
                      + " group by kind order by kind"))
          .as(
              "as contagens por tipo: 5 files, 15 methods, 3 packages e 5 types do golden,"
                  + " sem os alvos externos que as dependências criam")
          .containsExactly("FILE|5", "METHOD|15", "PACKAGE|3", "TYPE|5");
    }
  }
}
