package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage.STRUCTURAL_GRAPH;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RecordingFrameSink.Frame;
import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class MidRunCorruptionTest {

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
    registros.forEach(AnalysisRegistry::close);
  }

  @FunctionalInterface
  private interface NoDisco {
    void fazer() throws IOException;
  }

  private AnalysisRegistry registro(NoDisco antes) throws IOException {
    final AtomicBoolean jaCorrompeu = new AtomicBoolean();
    final StagePlan plano =
        RunFixtures.lightPlan()
            .with(
                STRUCTURAL_GRAPH,
                context -> {
                  if (!jaCorrompeu.compareAndSet(false, true)) {
                    return StageOutcome.skipped();
                  }
                  antes.fazer();
                  throw new SQLiteException(
                      "database disk image is malformed (sintético)",
                      SQLiteErrorCode.SQLITE_CORRUPT);
                });
    final AnalysisRegistry registro =
        new AnalysisRegistry(RunFixtures.settings(dados, quadros, plano, ManualNanoClock.frozen()));
    registros.add(registro);
    return registro;
  }

  private static List<Path> quarentena(Path banco) throws IOException {
    try (Stream<Path> pasta = Files.list(banco.getParent())) {
      return pasta.filter(arquivo -> arquivo.getFileName().toString().endsWith(".bak")).toList();
    }
  }

  private static int indice(List<Frame> quadros, String evento) {
    for (final Frame quadro : quadros) {
      if (evento.equals(quadro.event())) {
        return quadro.index();
      }
    }
    throw new AssertionError("nenhum " + evento + " em " + quadros);
  }

  private void verificarFalha(
      AnalysisRegistry registro, AnalysisId id, Frame terminal, ProtocolError erro) {
    assertThat(terminal.event()).as(terminal.line()).isEqualTo("analysis.failed");
    assertThat(terminal.payload().path("error")).isEqualTo(JSON.valueToTree(erro));
    final RunSnapshot retrato = registro.status(id).orElseThrow();
    assertThat(retrato.status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(retrato.failure()).isEqualTo(erro);
    assertThat(retrato.stages().get(STRUCTURAL_GRAPH).status()).isEqualTo(StageStatus.FAILED);
    assertThat(registro.readable(id))
        .as("o cache da análise não é mais o aberto aqui: nada dela é servido")
        .isEmpty();
  }

  @Test
  void reconstruidoOCacheAFalhaECorrompidaSemEscritaNoArquivoVelhoEAProximaCompleta()
      throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final Path banco = RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit());
    final AnalysisRegistry registro = registro(() -> {});

    final AnalysisId id = launch(registro, repositorio, defaults());
    final Frame terminal = quadros.awaitTerminal(id, AWAIT);
    RunFixtures.awaitRunEnd(id);

    verificarFalha(registro, id, terminal, ErrorCodes.analysisCacheCorrupted());
    final List<Frame> invalidados =
        quadros.frames().stream()
            .filter(quadro -> "cache.invalidated".equals(quadro.event()))
            .toList();
    assertThat(invalidados).as("um único cache.invalidated, o da reconstrução").hasSize(1);
    assertThat(invalidados.getFirst().payload().path("reason").asText()).isEqualTo("CORRUPT");
    assertThat(invalidados.getFirst().payload().path("error").path("code").asText())
        .isEqualTo(ErrorCodes.cacheCorrupt().code());
    assertThat(indice(quadros.of(id), "analysis.started"))
        .isLessThan(invalidados.getFirst().index());
    assertThat(invalidados.getFirst().index())
        .as("o cache.invalidated antes do terminal")
        .isLessThan(terminal.index());

    final List<Path> copias = quarentena(banco);
    assertThat(copias).as("o arquivo foi para a quarentena com as linhas da análise").hasSize(1);
    assertThat(RunFixtures.analysisRow(banco, id))
        .as("o arquivo novo nada sabe da análise que falhou")
        .isEmpty();
    final Map<String, Object> naQuarentena =
        RunFixtures.analysisRow(copias.getFirst(), id).orElseThrow();
    assertThat(naQuarentena.get("status"))
        .as("nenhum finish no arquivo corrompido")
        .isEqualTo("RUNNING");
    assertThat(naQuarentena.get("finished_at")).isNull();
    assertThat(
            RunFixtures.stageRows(copias.getFirst(), id).get(STRUCTURAL_GRAPH.name()).get("status"))
        .as("a linha do estágio que achou a corrupção não é escrita no arquivo corrompido")
        .isEqualTo("RUNNING");

    final AnalysisId segunda = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(segunda, AWAIT).event()).isEqualTo("analysis.completed");
    assertThat(registro.readable(segunda)).as("servida pelo cache trocado").isPresent();
    assertThat(RunFixtures.analysisRow(banco, segunda).orElseThrow().get("status"))
        .isEqualTo("COMPLETED");
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "tira a escrita da pasta por POSIX")
  void semReconstruirAFalhaECacheUnavailableEALinhaNaoFicaRunning() throws Exception {
    final Path repositorio = RunFixtures.multiModule(repositorios);
    final Path banco = RunFixtures.database(dados, repositorio, RunFixtures.multiModuleCommit());
    final Path pasta = banco.getParent();
    final AnalysisRegistry registro =
        registro(
            () ->
                Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("r-x------")));

    final AnalysisId id;
    final Frame terminal;
    try {
      id = launch(registro, repositorio, defaults());
      terminal = quadros.awaitTerminal(id, AWAIT);
      RunFixtures.awaitRunEnd(id);
    } finally {
      Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwx------"));
    }

    final ProtocolError erro = ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE);
    verificarFalha(registro, id, terminal, erro);
    assertThat(quadros.frames().stream().map(Frame::event))
        .as("nada foi renomeado: nenhum descarte a anunciar")
        .doesNotContain("cache.invalidated");
    assertThat(quarentena(banco)).as("nada foi renomeado").isEmpty();
    final Map<String, Object> linha = RunFixtures.analysisRow(banco, id).orElseThrow();
    assertThat(linha.get("status"))
        .as("o finish por melhor esforço no arquivo que ficou: a linha não fica RUNNING")
        .isEqualTo("FAILED");
    assertThat(linha.get("failure_code")).isEqualTo(erro.code());
    assertThat(linha.get("failure_detail")).isEqualTo(erro.detail());
    assertThat(linha.get("finished_at")).isNotNull();

    final AnalysisId segunda = launch(registro, repositorio, defaults());
    assertThat(quadros.awaitTerminal(segunda, AWAIT).event()).isEqualTo("analysis.completed");
    assertThat(RunFixtures.analysisRow(banco, segunda).orElseThrow().get("status"))
        .isEqualTo("COMPLETED");
  }
}
