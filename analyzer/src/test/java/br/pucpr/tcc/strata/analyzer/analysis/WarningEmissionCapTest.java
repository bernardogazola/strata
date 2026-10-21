package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WarningEmissionCapTest {

  private static final int BROKEN_FILES = AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE + 50;

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
  void maisFalhasDeParseQueOTetoEmitemExatamenteOTeto() throws Exception {
    final Path repositorio = repositorios.toRealPath().resolve("quebrados");
    try (var git = TestRepositories.init(repositorio)) {
      TestRepositories.write(repositorio, "src/main/java/p/Sao.java", "package p;\nclass Sao {}\n");
      for (int indice = 0; indice < BROKEN_FILES; indice++) {
        TestRepositories.write(
            repositorio,
            "src/main/java/p/E" + indice + ".java",
            "package p;\n\nclass E" + indice + " {\n  void m() {\n");
      }
      TestRepositories.commitAll(git, "quebrados\n");
    }
    registro =
        new AnalysisRegistry(
            RunFixtures.settings(dados, quadros, Stages.production(), ManualNanoClock.frozen()));

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);

    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.completed");
    final Map<String, Long> porCodigo =
        quadros.events(id, "analysis.warning").stream()
            .collect(
                Collectors.groupingBy(
                    quadro -> quadro.payload().path("error").path("code").asText(),
                    Collectors.counting()));
    assertThat(porCodigo)
        .as("o teto por código na emissão: exatamente MAX_SOURCE_WARNINGS_PER_CODE quadros")
        .containsExactly(
            Map.entry(
                SourceIssue.Code.SOURCE_PARSE_FAILED.name(),
                (long) AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE));
    assertThat(terminal.payload().path("completeness").path("parseFailures").asInt())
        .as("o número verdadeiro vai no fio como número")
        .isEqualTo(BROKEN_FILES);
  }
}
