package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.SQLiteErrorCode;

final class ElementWriterSearchNameTest {

  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final int SCHEMA_VERSION = 1;

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  @BeforeAll
  static void jgitHermetico() {
    HermeticSystemReader.install();
  }

  private ValidatedRepository reproduzir(String fixture) throws Exception {
    final Path directory = temp.toRealPath().resolve("repo").resolve(fixture);
    TestRepositories.reproduceCorpusFixture(fixture, directory);
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
  }

  private Map<String, String> extrairELer(String fixture, String sql) throws Exception {
    final ValidatedRepository repository = reproduzir(fixture);
    final PersistenceRoot persistence = PersistenceRoot.prepare(temp.toRealPath().resolve("data"));
    try (RepositoryCache cache =
        RepositoryCache.open(
                persistence,
                TestCaches.identityV01(),
                repository.snapshot().root(),
                "0.0.0-test",
                clock)
            .cache()) {
      final long analysisPk = extrair(cache, repository);
      return cache.connections().read(c -> chaveValor(c, sql, analysisPk));
    }
  }

  private long extrair(RepositoryCache cache, ValidatedRepository repository) throws SQLException {
    final List<SourceRoot> raizes = repository.layout().sourceRoots();
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

  private static Map<String, String> chaveValor(Connection c, String sql, long analysisPk)
      throws SQLException {
    final Map<String, String> linhas = new LinkedHashMap<>();
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final String valor = rows.getString(2);
          linhas.put(rows.getString(1), valor == null ? "NULL" : valor);
        }
      }
    }
    return linhas;
  }

  @Test
  void todoPacoteETipoInternoDoSimplePackagesGravaODobradoDoNomeSimples() throws Exception {
    final Map<String, String> gravados =
        extrairELer(
            "simple-packages",
            """
            SELECT element_id, search_name
              FROM code_element
             WHERE analysis_pk = ? AND kind IN ('PACKAGE', 'TYPE') AND is_external = 0
             ORDER BY element_id""");

    final Map<String, String> esperados = new LinkedHashMap<>();
    esperados.put("pkg:", "");
    esperados.put("pkg:br.pucpr", "pucpr");
    esperados.put("pkg:br.pucpr.orders", "orders");
    esperados.put("type:Bootstrap", "bootstrap");
    esperados.put("type:br.pucpr.Log", "log");
    esperados.put("type:br.pucpr.orders.Order", "order");
    esperados.put("type:br.pucpr.orders.Order$Builder", "builder");
    esperados.put("type:br.pucpr.orders.OrderService", "orderservice");
    assertThat(gravados).containsExactlyEntriesOf(esperados);
  }

  @Test
  void metodoArquivoETipoExternoGravamNulo() throws Exception {
    final Map<String, String> grupos =
        extrairELer(
            "simple-packages",
            """
            SELECT kind || '|' || is_external,
                   count(*) || ' linhas, ' || count(search_name) || ' com nome'
              FROM code_element
             WHERE analysis_pk = ? AND (kind IN ('METHOD', 'FILE') OR is_external = 1)
             GROUP BY kind, is_external
             ORDER BY kind, is_external""");

    assertThat(grupos).containsOnlyKeys("FILE|0", "METHOD|0", "TYPE|1");
    assertThat(grupos.get("FILE|0")).isEqualTo("5 linhas, 0 com nome");
    assertThat(grupos.get("METHOD|0")).isEqualTo("15 linhas, 0 com nome");
    assertThat(grupos.get("TYPE|1")).endsWith(", 0 com nome").doesNotStartWith("0 ");
  }

  @Test
  void oTipoExternoDoUuidExisteENaoTemNomeDeBusca() throws Exception {
    final Map<String, String> externo =
        extrairELer(
            "simple-packages",
            """
            SELECT element_id || '|' || is_external, search_name
              FROM code_element
             WHERE analysis_pk = ? AND element_id = 'type:java.util.UUID'""");

    assertThat(externo).containsExactly(Map.entry("type:java.util.UUID|1", "NULL"));
  }

  private static final String ELEMENTO_CRU =
      "INSERT INTO code_element (analysis_pk, element_id, kind, simple_name, qualified_name,"
          + " sort_key, is_external, search_name) VALUES (1, ?, ?, ?, ?, ?, ?, ?)";

  private static void analiseUm(Connection banco) throws SQLException {
    DaoTestDatabase.execute(
        banco,
        "INSERT INTO analysis (id, analysis_id, status, started_at, analyzer_version,"
            + " schema_version, protocol_version, config_hash, config_json, include_tests,"
            + " history_scope) VALUES (1, 'an_parent', 'RUNNING', 0, '0.1.0', 1, 1, 'h', '{}',"
            + " 0, 'ALL')");
  }

  static Stream<Arguments> formasIlegais() {
    return Stream.of(
        Arguments.of("tipo interno sem nome", "type:a.Order", "TYPE", "Order", 0, null),
        Arguments.of("pacote sem nome", "pkg:a", "PACKAGE", "a", 0, null),
        Arguments.of("método com nome", "method:a.Order#run()", "METHOD", "run", 0, "run"),
        Arguments.of(
            "arquivo com nome", "file:src/a/Order.java", "FILE", "Order.java", 0, "order.java"),
        Arguments.of("tipo externo com nome", "type:java.util.UUID", "TYPE", "UUID", 1, "uuid"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formasIlegais")
  void oCheckRecusaONomeDeBuscaForaDaRegra(
      String caso, String id, String kind, String nome, int externo, String busca)
      throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      analiseUm(banco);

      assertThatThrownBy(
              () ->
                  DaoTestDatabase.execute(
                      banco, ELEMENTO_CRU, id, kind, nome, nome, nome, externo, busca))
          .as(caso)
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      assertThat(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM code_element")).isEqualTo("0");
    }
  }

  @Test
  void asFormasValidasPassamPeloCheck() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      analiseUm(banco);

      assertThatCode(
              () -> {
                DaoTestDatabase.execute(banco, ELEMENTO_CRU, "pkg:", "PACKAGE", "", "", "", 0, "");
                DaoTestDatabase.execute(
                    banco,
                    ELEMENTO_CRU,
                    "type:a.Order",
                    "TYPE",
                    "Order",
                    "a.Order",
                    "a.Order",
                    0,
                    "order");
                DaoTestDatabase.execute(
                    banco,
                    ELEMENTO_CRU,
                    "method:a.Order#run()",
                    "METHOD",
                    "run",
                    "run",
                    "run",
                    0,
                    null);
                DaoTestDatabase.execute(
                    banco,
                    ELEMENTO_CRU,
                    "type:java.util.UUID",
                    "TYPE",
                    "UUID",
                    "java.util.UUID",
                    "java.util.UUID",
                    1,
                    null);
              })
          .doesNotThrowAnyException();
      assertThat(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM code_element")).isEqualTo("4");
    }
  }
}
