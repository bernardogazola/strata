package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteException;

final class StructuralGraphStageWriteUnitTest {

  private static final long CYCLIC_METRIC_ROWS = 6 + 4;

  private static final long CYCLIC_PACKAGE_EDGES = 3;

  private static final String ELEMENT_PK =
      "SELECT id FROM code_element WHERE analysis_pk = ? AND element_id = ?";

  private static final String METRIC_ROWS =
      "SELECT count(*) FROM graph_metric WHERE analysis_pk = ?";

  private static final String EDGE_ROWS = "SELECT count(*) FROM package_edge WHERE analysis_pk = ?";

  @TempDir Path dados;
  @TempDir Path repositorios;

  private final RecordingFrameSink quadros = new RecordingFrameSink();
  private AnalysisRegistry registro;

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void fechar() {
    if (registro != null) {
      registro.close();
    }
  }

  @Test
  void umaFalhaNoInsertDePackageEdgeDesfazAsLinhasDeGraphMetricDaMesmaTransacao() throws Exception {
    final Analise analise = analisar(true);

    assertThat(analise.terminal().event())
        .as("terminal: " + analise.terminal().line())
        .isEqualTo("analysis.failed");
    assertThat(analise.terminal().payload().path("error").path("code").asText())
        .isEqualTo("INTERNAL_UNEXPECTED");
    assertThat(analise.terminal().payload().path("error").path("detail").asText())
        .as("a SQLException original da chave repetida, nunca embrulhada")
        .isEqualTo(SQLiteException.class.getName());
    assertThat(estagio(analise).path("status").asText()).isEqualTo("FAILED");
    assertThat(contar(analise, METRIC_ROWS))
        .as("as linhas de graph_metric gravadas antes da falha se desfizeram com ela")
        .isZero();
    assertThat(contar(analise, EDGE_ROWS))
        .as("só a aresta que o executor confirmou antes do estágio")
        .isEqualTo(1);
  }

  @Test
  void semAColisaoOMesmoExecutorGravaAsDuasTabelas() throws Exception {
    final Analise analise = analisar(false);

    assertThat(analise.terminal().event())
        .as("terminal: " + analise.terminal().line())
        .isEqualTo("analysis.completed");
    assertThat(estagio(analise).path("status").asText()).isEqualTo("COMPLETED");
    assertThat(contar(analise, METRIC_ROWS)).isEqualTo(CYCLIC_METRIC_ROWS);
    assertThat(contar(analise, EDGE_ROWS)).isEqualTo(CYCLIC_PACKAGE_EDGES);
  }

  private record Analise(AnalysisId id, Path banco, long chave, Frame terminal, Frame grafo) {}

  private Analise analisar(boolean colidir) throws Exception {
    final Path caminho = repositorios.toRealPath().resolve("cyclic");
    final String raiz = TestRepositories.reproduceCorpusFixture("cyclic", caminho).name();
    final StagePlan plano = Stages.production().with(STRUCTURAL_GRAPH, executor(colidir));
    registro =
        new AnalysisRegistry(
            RunFixtures.settings(
                dados, quadros, plano, ManualNanoClock.stepping(Duration.ofMillis(150))));

    final AnalysisId id = RunFixtures.launch(registro, caminho, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    RunFixtures.awaitRunEnd(id);

    final Path banco = RunFixtures.database(dados, caminho, raiz);
    final long chave =
        ((Number) RunFixtures.analysisRow(banco, id).orElseThrow().get("id")).longValue();
    final List<Frame> grafo =
        quadros.events(id, "analysis.stage.completed").stream()
            .filter(quadro -> STRUCTURAL_GRAPH.name().equals(quadro.stage()))
            .toList();
    assertThat(grafo).as("um analysis.stage.completed do STRUCTURAL_GRAPH").hasSize(1);
    return new Analise(id, banco, chave, terminal, grafo.get(0));
  }

  private static StageExecutor executor(boolean colidir) {
    final StructuralGraphStage estagio = new StructuralGraphStage(ExternalPackages.load());
    return context -> {
      if (colidir) {
        context
            .connections()
            .write(
                connection -> {
                  PackageEdgeDao.insert(
                      connection,
                      context.analysisPk(),
                      List.of(arestaQueColide(connection, context)));
                  return null;
                });
      }
      return estagio.execute(context);
    };
  }

  private static PackageEdgeRow arestaQueColide(Connection connection, StageContext context)
      throws SQLException {
    return new PackageEdgeRow(
        chaveDe(connection, context.analysisPk(), "pkg:com.x.app"),
        chaveDe(connection, context.analysisPk(), "pkg:com.x.orders"),
        1,
        4,
        ResolutionQuality.RESOLVED,
        Map.of(
            DependencyType.IMPORTS, 1,
            DependencyType.CALLS, 1,
            DependencyType.FIELD_TYPE, 1,
            DependencyType.PARAMETER_TYPE, 1));
  }

  private static long chaveDe(Connection connection, long analysisPk, String elementId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ELEMENT_PK)) {
      statement.setLong(1, analysisPk);
      statement.setString(2, elementId);
      try (ResultSet linhas = statement.executeQuery()) {
        assertThat(linhas.next()).as("o elemento " + elementId + " da análise").isTrue();
        return linhas.getLong(1);
      }
    }
  }

  private static JsonNode estagio(Analise analise) {
    return analise.grafo().payload();
  }

  private static long contar(Analise analise, String consulta) throws SQLException {
    return RunFixtures.count(analise.banco(), consulta, analise.chave());
  }
}
