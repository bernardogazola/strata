package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StagePlanTest {

  private static Map<AnalysisStage, StageExecutor> todosOsOito() {
    final Map<AnalysisStage, StageExecutor> executores = new EnumMap<>(AnalysisStage.class);
    for (final AnalysisStage estagio : StagePlan.SPI_STAGES) {
      executores.put(estagio, SkippedStage.INSTANCE);
    }
    return executores;
  }

  @Test
  void osEstagiosDaSpiSaoOsOitoDoSourceAnalysisAoPersistenceEmOrdem() {
    assertThat(StagePlan.SPI_STAGES)
        .containsExactly(
            AnalysisStage.SOURCE_ANALYSIS,
            AnalysisStage.GIT_HISTORY_CHEAP,
            AnalysisStage.STRUCTURAL_GRAPH,
            AnalysisStage.GIT_HISTORY_DETAILED,
            AnalysisStage.EVOLUTIONARY_COUPLING,
            AnalysisStage.HOTSPOT_PROFILES,
            AnalysisStage.GUIDED_TOUR,
            AnalysisStage.PERSISTENCE);
    assertThatThrownBy(() -> StagePlan.SPI_STAGES.add(AnalysisStage.LAYOUT_DISCOVERY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aceitaOsOitoEDevolveCadaExecutor() {
    final StagePlan plano = new StagePlan(todosOsOito());

    assertThat(plano.executors().keySet()).isEqualTo(StagePlan.SPI_STAGES);
    for (final AnalysisStage estagio : StagePlan.SPI_STAGES) {
      assertThat(plano.executor(estagio)).isSameAs(SkippedStage.INSTANCE);
    }
  }

  @Test
  void recusaUmEstagioFaltando() {
    final Map<AnalysisStage, StageExecutor> semPersistence = todosOsOito();
    semPersistence.remove(AnalysisStage.PERSISTENCE);

    assertThatThrownBy(() -> new StagePlan(semPersistence))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recusaOsEstagiosDoPrologo() {
    for (final AnalysisStage prologo :
        new AnalysisStage[] {AnalysisStage.REPOSITORY_VALIDATION, AnalysisStage.LAYOUT_DISCOVERY}) {
      final Map<AnalysisStage, StageExecutor> comPrologo = todosOsOito();
      comPrologo.put(prologo, SkippedStage.INSTANCE);

      assertThatThrownBy(() -> new StagePlan(comPrologo))
          .as(prologo.name())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void recusaUmExecutorNulo() {
    final Map<AnalysisStage, StageExecutor> comNulo = todosOsOito();
    comNulo.put(AnalysisStage.GUIDED_TOUR, null);

    assertThatThrownBy(() -> new StagePlan(comNulo)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCopiaEImutavelEDefensiva() {
    final Map<AnalysisStage, StageExecutor> origem = todosOsOito();
    final StagePlan plano = new StagePlan(origem);
    final StageExecutor outro = context -> StageOutcome.skipped();

    origem.put(AnalysisStage.PERSISTENCE, outro);

    assertThat(plano.executor(AnalysisStage.PERSISTENCE)).isSameAs(SkippedStage.INSTANCE);
    assertThatThrownBy(() -> plano.executors().put(AnalysisStage.PERSISTENCE, outro))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void withTrocaUmaEntradaSemMudarOOriginal() {
    final StagePlan plano = new StagePlan(todosOsOito());
    final StageExecutor travado = context -> StageOutcome.completed(StageCounters.none());

    final StagePlan trocado = plano.with(AnalysisStage.STRUCTURAL_GRAPH, travado);

    assertThat(trocado.executor(AnalysisStage.STRUCTURAL_GRAPH)).isSameAs(travado);
    assertThat(plano.executor(AnalysisStage.STRUCTURAL_GRAPH)).isSameAs(SkippedStage.INSTANCE);
    assertThatThrownBy(() -> plano.with(AnalysisStage.REPOSITORY_VALIDATION, travado))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void executorDeUmEstagioDoPrologoERecusado() {
    final StagePlan plano = new StagePlan(todosOsOito());

    assertThatThrownBy(() -> plano.executor(AnalysisStage.LAYOUT_DISCOVERY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oPlanoDeProducaoMapeiaOsOito() {
    assertThat(Stages.production().executors().keySet()).isEqualTo(StagePlan.SPI_STAGES);
  }

  @Test
  void oEstagioPuladoNaoContaNada() {
    assertThat(SkippedStage.INSTANCE.execute(null)).isEqualTo(StageOutcome.skipped());
    assertThat(StageOutcome.skipped().status()).isEqualTo(StageStatus.SKIPPED);
    assertThat(StageOutcome.skipped().counters()).isEqualTo(StageCounters.none());
  }
}
