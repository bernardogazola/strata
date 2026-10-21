package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class ProgressReporterTest {

  private static final Duration INTERVALO =
      Duration.ofMillis(AnalysisConstants.PROGRESS_THROTTLE_MILLIS);

  private static final long UM_MS = TimeUnit.MILLISECONDS.toNanos(1);

  private static final AnalysisId ID = AnalysisId.parse("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  private final AtomicLong agora = new AtomicLong(0);

  private final ProgressThrottle throttle = new ProgressThrottle(agora::get, INTERVALO);

  private final List<ProgressTick> emitidos = new ArrayList<>();

  private final ProgressReporter fonte =
      new ProgressReporter(ID, AnalysisStage.SOURCE_ANALYSIS, throttle, emitidos::add);

  private void avancar(Duration quanto) {
    agora.addAndGet(quanto.toNanos());
  }

  @Test
  void oPrimeiroReportSaiComOEstagioDoCanalAUnidadeEOTotal() {
    fonte.report(ProgressUnit.JAVA_FILES, 0, 120L);

    assertThat(emitidos)
        .containsExactly(
            new ProgressTick(AnalysisStage.SOURCE_ANALYSIS, ProgressUnit.JAVA_FILES, 0, 120L));
  }

  @Test
  void umTotalDesconhecidoViajaComoNulo() {
    final ProgressReporter validacao =
        new ProgressReporter(ID, AnalysisStage.REPOSITORY_VALIDATION, throttle, emitidos::add);

    validacao.report(ProgressUnit.JAVA_FILES, 0, null);
    avancar(INTERVALO);
    validacao.report(ProgressUnit.JAVA_FILES, 1_024, null);

    assertThat(emitidos)
        .containsExactly(
            new ProgressTick(AnalysisStage.REPOSITORY_VALIDATION, ProgressUnit.JAVA_FILES, 0, null),
            new ProgressTick(
                AnalysisStage.REPOSITORY_VALIDATION, ProgressUnit.JAVA_FILES, 1_024, null));
  }

  @Test
  void osReportsDentroDoIntervaloSaoDescartados() {
    fonte.report(ProgressUnit.JAVA_FILES, 0, 120L);
    avancar(Duration.ofNanos(UM_MS));
    fonte.report(ProgressUnit.JAVA_FILES, 10, 120L);
    fonte.report(ProgressUnit.JAVA_FILES, 20, 120L);
    avancar(INTERVALO);
    fonte.report(ProgressUnit.JAVA_FILES, 30, 120L);

    assertThat(emitidos)
        .extracting(ProgressTick::completed)
        .as("o primeiro passa, os dois de +1 ms caem, o de +101 ms passa")
        .containsExactly(0L, 30L);
  }

  @Test
  void umReportMenorDepoisDeUmMaiorNuncaEmiteValorMenor() {
    fonte.report(ProgressUnit.JAVA_FILES, 7, 120L);
    avancar(INTERVALO);

    fonte.report(ProgressUnit.JAVA_FILES, 5, 120L);

    assertThat(emitidos).isNotEmpty();
    assertThat(emitidos.getFirst().completed()).isEqualTo(7);
    assertThat(emitidos)
        .extracting(ProgressTick::completed)
        .as("o produtor fora de ordem não faz a barra voltar: accumulateAndGet(max)")
        .allMatch(completed -> completed >= 7)
        .isSorted();
  }

  @Test
  void aMonotoniaValeTambemContraUmValorQueOEstrangulamentoDescartou() {
    fonte.report(ProgressUnit.JAVA_FILES, 7, 120L);
    avancar(Duration.ofNanos(UM_MS));
    fonte.report(ProgressUnit.JAVA_FILES, 9, 120L);
    avancar(INTERVALO);

    fonte.report(ProgressUnit.JAVA_FILES, 5, 120L);

    assertThat(emitidos)
        .extracting(ProgressTick::completed)
        .as("nada abaixo do maior já emitido, mesmo que o 9 tenha caído no estrangulamento")
        .isSorted()
        .allMatch(completed -> completed >= 7);
  }

  @Test
  void finishSaiSempreMesmoDentroDoIntervaloEMesmoRepetido() {
    fonte.report(ProgressUnit.JAVA_FILES, 0, 120L);
    avancar(Duration.ofNanos(UM_MS));

    fonte.finish(ProgressUnit.JAVA_FILES, 120, 120L);
    fonte.finish(ProgressUnit.JAVA_FILES, 120, 120L);

    assertThat(emitidos)
        .containsExactly(
            new ProgressTick(AnalysisStage.SOURCE_ANALYSIS, ProgressUnit.JAVA_FILES, 0, 120L),
            new ProgressTick(AnalysisStage.SOURCE_ANALYSIS, ProgressUnit.JAVA_FILES, 120, 120L),
            new ProgressTick(AnalysisStage.SOURCE_ANALYSIS, ProgressUnit.JAVA_FILES, 120, 120L));
  }

  @Test
  void finishPassaComoTerminalECarimbaOInstante() {
    fonte.report(ProgressUnit.JAVA_FILES, 0, 120L);
    avancar(INTERVALO);
    avancar(INTERVALO);

    fonte.finish(ProgressUnit.JAVA_FILES, 120, 120L);
    avancar(Duration.ofNanos(UM_MS));
    fonte.report(ProgressUnit.JAVA_FILES, 120, 120L);

    assertThat(emitidos)
        .extracting(ProgressTick::completed)
        .as("o report de +1 ms depois do finish cai: o terminal carimbou")
        .containsExactly(0L, 120L);
  }

  @Test
  void doisCanaisDoMesmoIdEmEstagiosDiferentesNaoSeEstrangulam() {
    final List<ProgressTick> historico = new ArrayList<>();
    final ProgressReporter irmao =
        new ProgressReporter(ID, AnalysisStage.GIT_HISTORY_CHEAP, throttle, historico::add);

    fonte.report(ProgressUnit.JAVA_FILES, 0, 120L);
    irmao.report(ProgressUnit.COMMITS, 0, null);
    avancar(INTERVALO);
    fonte.report(ProgressUnit.JAVA_FILES, 40, 120L);
    irmao.report(ProgressUnit.COMMITS, 300, null);

    assertThat(emitidos).extracting(ProgressTick::completed).containsExactly(0L, 40L);
    assertThat(historico)
        .containsExactly(
            new ProgressTick(AnalysisStage.GIT_HISTORY_CHEAP, ProgressUnit.COMMITS, 0, null),
            new ProgressTick(AnalysisStage.GIT_HISTORY_CHEAP, ProgressUnit.COMMITS, 300, null));
  }
}
