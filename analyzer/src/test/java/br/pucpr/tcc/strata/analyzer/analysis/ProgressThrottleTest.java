package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class ProgressThrottleTest {

  private static final Duration INTERVALO =
      Duration.ofMillis(AnalysisConstants.PROGRESS_THROTTLE_MILLIS);

  private static final long INTERVALO_NANOS = INTERVALO.toNanos();

  private static final long UM_MS = TimeUnit.MILLISECONDS.toNanos(1);

  private static final long T0 = -7_000_000_000L;

  private static final int THREADS = 8;

  private static final AnalysisId ID = AnalysisId.parse("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W");

  private static final AnalysisId OUTRO_ID = AnalysisId.parse("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1X");

  private final AtomicLong agora = new AtomicLong(T0);

  private final ProgressThrottle throttle = new ProgressThrottle(agora::get, INTERVALO);

  @Test
  void oPrimeiroPassoDeUmParEAdmitidoComoIsento() {
    assertThat(throttle.admit(ID, AnalysisStage.REPOSITORY_VALIDATION, false))
        .as("a chave nova passa: é o (0, null) que tem de sair em menos de 250 ms")
        .isTrue();
  }

  @Test
  void umMilissegundoAntesDoIntervaloRecusaENoIntervaloAdmite() {
    throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false);

    agora.set(T0 + INTERVALO_NANOS - UM_MS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("+99 ms: dentro do intervalo")
        .isFalse();

    agora.set(T0 + INTERVALO_NANOS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("+100 ms: o intervalo é \"no mínimo\", inclusive")
        .isTrue();
  }

  @Test
  void oPassoRecusadoNaoReiniciaOIntervalo() {
    throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false);

    agora.set(T0 + INTERVALO_NANOS / 2);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isFalse();
    agora.set(T0 + INTERVALO_NANOS);

    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("só o passo admitido carimba; um recusado que carimbasse adiaria o seguinte sem fim")
        .isTrue();
  }

  @Test
  void oTerminalUmMilissegundoDepoisEAdmitidoECarimbaOInstante() {
    throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false);

    agora.set(T0 + UM_MS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, true))
        .as("o terminal é isento: precede o stage.completed do estágio, sempre")
        .isTrue();
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, true))
        .as("isento também no mesmo instante")
        .isTrue();

    agora.set(T0 + INTERVALO_NANOS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("o terminal carimbou +1 ms: +100 ms está 99 ms depois dele")
        .isFalse();
    agora.set(T0 + UM_MS + INTERVALO_NANOS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
  }

  @Test
  void doisEstagiosEDoisIdsSaoChavesIndependentes() {
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();

    agora.set(T0 + UM_MS);
    assertThat(throttle.admit(ID, AnalysisStage.GIT_HISTORY_CHEAP, false))
        .as("o par concorre sob o mesmo id: uma chave por id juntaria os dois passos")
        .isTrue();
    assertThat(throttle.admit(OUTRO_ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isFalse();

    agora.set(T0 + INTERVALO_NANOS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
    assertThat(throttle.admit(ID, AnalysisStage.GIT_HISTORY_CHEAP, false))
        .as("o carimbo do GIT_HISTORY_CHEAP é +1 ms, e não o do SOURCE_ANALYSIS")
        .isFalse();
    agora.set(T0 + UM_MS + INTERVALO_NANOS);
    assertThat(throttle.admit(ID, AnalysisStage.GIT_HISTORY_CHEAP, false)).isTrue();
    assertThat(throttle.admit(OUTRO_ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
  }

  @Test
  void forgetFazOProximoPassoDaqueleIdSerOPrimeiroDeNovo() {
    throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false);
    throttle.admit(OUTRO_ID, AnalysisStage.SOURCE_ANALYSIS, false);
    agora.set(T0 + UM_MS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isFalse();

    throttle.forget(ID);

    agora.set(T0 + 2 * UM_MS);
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("esquecido, o id volta a ter chave nova")
        .isTrue();
    assertThat(throttle.admit(OUTRO_ID, AnalysisStage.SOURCE_ANALYSIS, false))
        .as("forget de um id não toca no outro")
        .isFalse();
  }

  @Test
  void forgetDeUmIdQueNuncaPassouNaoLanca() {
    throttle.forget(ID);

    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void oitoThreadsNumaChaveNovaComORelogioParadoAdmitemExatamenteUm() throws Exception {
    final long admitidos = martelar(() -> throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false));

    assertThat(admitidos).isEqualTo(1);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void oitoThreadsNumaChaveVencidaComORelogioParadoAdmitemExatamenteUm() throws Exception {
    assertThat(throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)).isTrue();
    agora.set(T0 + INTERVALO_NANOS);

    final long admitidos = martelar(() -> throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false));

    assertThat(admitidos).isEqualTo(1);
  }

  @Test
  void oRelogioAndando10MsNumSegundoAdmiteOnzeNumaThread() {
    int admitidos = 0;
    for (long passo = 0; passo <= 100; passo++) {
      agora.set(T0 + passo * 10 * UM_MS);
      if (throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false)) {
        admitidos++;
      }
    }

    assertThat(admitidos).isEqualTo(11);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void oRelogioAndando10MsPorLeituraNumSegundoComOitoThreadsAdmiteNoMaximoOnze() throws Exception {
    final ProgressThrottle andando = new ProgressThrottle(relogioQueAnda(), INTERVALO);

    final long admitidos = martelar(() -> andando.admit(ID, AnalysisStage.SOURCE_ANALYSIS, false));

    assertThat(admitidos)
        .as("no máximo um por intervalo em 1 s, mais o primeiro: ≤ 10 por segundo + 1")
        .isBetween(1L, 11L);
  }

  @Test
  void nenhumParDePassosNaoIsentosDaMesmaChaveFicaAMenosDeUmIntervalo() {
    final SplittableRandom sorteio = new SplittableRandom(27);
    final List<Long> naoIsentosAdmitidos = new ArrayList<>();
    long carimbo = Long.MIN_VALUE;
    long instante = T0;
    boolean primeiro = true;
    for (int passo = 0; passo < 5_000; passo++) {
      instante += sorteio.nextLong(0, 3 * UM_MS * 10);
      agora.set(instante);
      final boolean terminal = sorteio.nextInt(50) == 0;

      final boolean admitido = throttle.admit(ID, AnalysisStage.SOURCE_ANALYSIS, terminal);

      if (primeiro || terminal) {
        assertThat(admitido).as("o primeiro e o terminal são isentos (passo %d)", passo).isTrue();
      } else {
        assertThat(admitido)
            .as("passo %d, %d ns depois do carimbo", passo, instante - carimbo)
            .isEqualTo(instante - carimbo >= INTERVALO_NANOS);
        if (admitido) {
          naoIsentosAdmitidos.add(instante);
        }
      }
      if (admitido) {
        carimbo = instante;
      }
      primeiro = false;
    }

    assertThat(naoIsentosAdmitidos)
        .as("a sequência exercita o estrangulamento")
        .hasSizeGreaterThan(100);
    for (int i = 1; i < naoIsentosAdmitidos.size(); i++) {
      assertThat(naoIsentosAdmitidos.get(i) - naoIsentosAdmitidos.get(i - 1))
          .isGreaterThanOrEqualTo(INTERVALO_NANOS);
    }
  }

  private static LongSupplier relogioQueAnda() {
    final AtomicLong leituras = new AtomicLong();
    final long passo = 10 * UM_MS;
    final long ultimo = 100;
    return () -> T0 + Math.min(leituras.getAndIncrement(), ultimo) * passo;
  }

  private static long martelar(Admissao admitir) throws Exception {
    final CountDownLatch largada = new CountDownLatch(1);
    final ExecutorService threads = Executors.newFixedThreadPool(THREADS);
    try {
      final List<Future<Long>> contagens = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        contagens.add(
            threads.submit(
                () -> {
                  largada.await();
                  long admitidos = 0;
                  for (int chamada = 0; chamada < 1_000; chamada++) {
                    if (admitir.admit()) {
                      admitidos++;
                    }
                  }
                  return admitidos;
                }));
      }
      largada.countDown();
      long total = 0;
      for (final Future<Long> contagem : contagens) {
        total += contagem.get(20, TimeUnit.SECONDS);
      }
      return total;
    } finally {
      threads.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface Admissao {
    boolean admit();
  }
}
