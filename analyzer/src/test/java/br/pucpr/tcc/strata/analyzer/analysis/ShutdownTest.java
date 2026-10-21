package br.pucpr.tcc.strata.analyzer.analysis;

import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.AWAIT;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.awaitLatch;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.defaults;
import static br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.launch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.PathFlavor;
import br.pucpr.tcc.strata.analyzer.persistence.PersistenceRoot;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryIdentity;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

final class ShutdownTest {

  private static final Duration CLOSE_DEADLINE =
      Duration.ofMillis(AnalysisConstants.RUN_STOP_WAIT_MILLIS + 500L);

  private static final Duration STUCK_WAIT = Duration.ofMillis(300);

  private static final String SHUTDOWN =
      "{\"id\":\"req_01JQ8ZF1B2C3D4E5F6G7H8J9K2\",\"kind\":\"request\","
          + "\"method\":\"system.shutdown\",\"payload\":{},\"protocol\":1}";

  @TempDir Path repositorios;

  private final List<AnalysisRegistry> registros = new ArrayList<>();
  private final List<CountDownLatch> trincos = new ArrayList<>();

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterEach
  void soltarEFechar() {
    trincos.forEach(CountDownLatch::countDown);
    for (final AnalysisRegistry registro : registros) {
      registro.close();
    }
  }

  private Path repositorio(String nome) throws Exception {
    return RunFixtures.multiModule(Files.createDirectories(repositorios.resolve(nome)));
  }

  private StagePlan planoQuePrende(Path preso, CountDownLatch dentro, boolean cooperativo) {
    final CountDownLatch soltar = new CountDownLatch(1);
    trincos.add(soltar);
    final StageExecutor cooperativa = RunFixtures.pollingUntilCancelled(dentro, AWAIT);
    return RunFixtures.lightPlan()
        .with(
            AnalysisStage.STRUCTURAL_GRAPH,
            context -> {
              if (!context.inputs().repository().snapshot().root().equals(preso)) {
                return StageOutcome.skipped();
              }
              if (cooperativo) {
                return cooperativa.execute(context);
              }
              dentro.countDown();
              awaitLatch(soltar, AWAIT);
              return StageOutcome.skipped();
            });
  }

  private WireHarness laco(Path dados, StagePlan plano, Duration espera) throws IOException {
    final WireHarness fio =
        new WireHarness(
            escritor -> {
              try {
                return RunFixtures.settings(dados, escritor, plano, ManualNanoClock.frozen())
                    .withRunStopWait(espera);
              } catch (IOException falha) {
                throw new UncheckedIOException(falha);
              }
            });
    registros.add(fio.registry());
    return fio;
  }

  private static int esperarEvento(WireHarness fio, AnalysisId id, String evento)
      throws InterruptedException {
    return fio.awaitLine(
        n ->
            evento.equals(n.path("event").asText())
                && id.value().equals(n.path("payload").path("analysisId").asText()),
        AWAIT,
        evento + " de " + id);
  }

  private static RepositoryIdentity identidade(Path repositorio) throws IOException {
    return RepositoryIdentity.of(
        repositorio.toString(), PathFlavor.host(), RunFixtures.multiModuleCommit());
  }

  private static void reabrir(Path dados, Path repositorio) throws Exception {
    final RepositoryCache cache =
        RepositoryCache.open(
                PersistenceRoot.prepare(dados),
                identidade(repositorio),
                repositorio,
                RunFixtures.ANALYZER_VERSION,
                InstantSource.system())
            .cache();
    cache.close();
  }

  private static AnalysisId umaCompletaEOutraPresa(
      WireHarness fio, Path completa, Path presa, CountDownLatch dentro) throws Exception {
    final AnalysisId primeira = launch(fio.registry(), completa, defaults());
    esperarEvento(fio, primeira, "analysis.completed");
    RunFixtures.awaitRunEnd(primeira);
    final AnalysisId segunda = launch(fio.registry(), presa, defaults());
    assertThat(awaitLatch(dentro, AWAIT)).as("a segunda análise chegou ao estágio preso").isTrue();
    return segunda;
  }

  private void verificarParadaLimpa(
      WireHarness fio, Path dados, Path completa, Path presa, AnalysisId id, long comeco)
      throws Exception {
    assertThat(fio.awaitExit(AWAIT)).as("o laço volta com saída limpa").isZero();
    fio.registry().close();
    final Duration levou = Duration.ofNanos(System.nanoTime() - comeco);

    assertThat(levou)
        .as("cancelar, juntar e fechar todo cache cabe em RUN_STOP_WAIT_MILLIS + 500 ms")
        .isLessThanOrEqualTo(CLOSE_DEADLINE);
    esperarEvento(fio, id, "analysis.cancelled");
    assertThat(fio.registry().status(id).orElseThrow().status())
        .isEqualTo(AnalysisStatus.CANCELLED);
    assertThat(
            RunFixtures.analysisRow(
                    RunFixtures.database(dados, presa, RunFixtures.multiModuleCommit()), id)
                .orElseThrow()
                .get("status"))
        .isEqualTo("CANCELLED");
    reabrir(dados, presa);
    reabrir(dados, completa);
  }

  @Test
  void oShutdownNoMeioRespondeCancelaEFechaTodoCacheDentroDoPrazo(@TempDir Path dados)
      throws Exception {
    final Path completa = repositorio("a");
    final Path presa = repositorio("b");
    final CountDownLatch dentro = new CountDownLatch(1);
    try (WireHarness fio =
        laco(
            dados,
            planoQuePrende(presa, dentro, true),
            Duration.ofMillis(AnalysisConstants.RUN_STOP_WAIT_MILLIS))) {
      final AnalysisId id = umaCompletaEOutraPresa(fio, completa, presa, dentro);

      final long comeco = System.nanoTime();
      fio.send(SHUTDOWN);
      final int resposta =
          fio.awaitLine(
              n -> "req_01JQ8ZF1B2C3D4E5F6G7H8J9K2".equals(n.path("id").asText()),
              AWAIT,
              "a resposta do shutdown");
      final JsonNode quadro = WireHarness.parse(fio.lines().get(resposta));
      assertThat(quadro.path("ok").asBoolean()).as("a resposta é escrita").isTrue();

      verificarParadaLimpa(fio, dados, completa, presa, id, comeco);
    }
  }

  @Test
  void oEofNoMeioCancelaEFechaTodoCacheDentroDoPrazo(@TempDir Path dados) throws Exception {
    final Path completa = repositorio("a");
    final Path presa = repositorio("b");
    final CountDownLatch dentro = new CountDownLatch(1);
    try (WireHarness fio =
        laco(
            dados,
            planoQuePrende(presa, dentro, true),
            Duration.ofMillis(AnalysisConstants.RUN_STOP_WAIT_MILLIS))) {
      final AnalysisId id = umaCompletaEOutraPresa(fio, completa, presa, dentro);

      final long comeco = System.nanoTime();
      fio.closeInput();

      verificarParadaLimpa(fio, dados, completa, presa, id, comeco);
    }
  }

  @Test
  void umEstagioQueIgnoraOTokenDeixaSoOCacheDeleAbertoDepoisDaEspera(
      @TempDir(cleanup = CleanupMode.NEVER) Path dados) throws Exception {
    final Path completa = repositorio("a");
    final Path presa = repositorio("b");
    final CountDownLatch dentro = new CountDownLatch(1);
    try (WireHarness fio = laco(dados, planoQuePrende(presa, dentro, false), STUCK_WAIT)) {
      final AnalysisId id = umaCompletaEOutraPresa(fio, completa, presa, dentro);

      final long comeco = System.nanoTime();
      fio.closeInput();
      assertThat(fio.awaitExit(AWAIT)).isZero();
      fio.registry().close();
      final Duration levou = Duration.ofNanos(System.nanoTime() - comeco);

      assertThat(levou)
          .as("o fechamento espera a análise por runStopWait, e não mais")
          .isGreaterThanOrEqualTo(STUCK_WAIT)
          .isLessThan(STUCK_WAIT.plus(Duration.ofSeconds(2)));
      assertThatThrownBy(() -> reabrir(dados, presa))
          .as("o cache da análise que não parou fica aberto: o lock ainda é deste processo")
          .isInstanceOf(IllegalStateException.class);
      reabrir(dados, completa);
      assertThat(
              RunFixtures.analysisRow(
                      RunFixtures.database(dados, presa, RunFixtures.multiModuleCommit()), id)
                  .orElseThrow()
                  .get("status"))
          .as("a linha fica RUNNING")
          .isEqualTo("RUNNING");
    }
  }
}
