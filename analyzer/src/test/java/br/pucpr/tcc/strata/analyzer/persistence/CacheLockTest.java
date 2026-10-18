package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class CacheLockTest {

  private static final Duration POUCA_ESPERA = Duration.ofMillis(50);

  @TempDir private Path temp;

  private Path pasta;

  @BeforeEach
  void pasta() throws Exception {
    pasta = Files.createDirectories(temp.toRealPath().resolve(TestCaches.V01_KEY));
  }

  @Test
  void umaSegundaAquisicaoNaMesmaJvmERecusadaAntesDeAbrirCanalESemSoltarAPrimeira()
      throws Exception {
    final AtomicInteger tentativas = new AtomicInteger();
    final CacheLock primeiro = CacheLock.acquire(pasta, POUCA_ESPERA);
    try {
      assertThatThrownBy(
              () ->
                  CacheLock.acquire(
                      pasta,
                      POUCA_ESPERA,
                      canal -> {
                        tentativas.incrementAndGet();
                        return canal.tryLock();
                      }))
          .as("um segundo canal sobre o arquivo soltaria o lock do primeiro (POSIX)")
          .isInstanceOf(IllegalStateException.class);
      assertThat(tentativas).as("recusada pelo registro, antes de qualquer canal").hasValue(0);
      assertThat(CacheLock.isHeldInThisProcess(pasta)).isTrue();
    } finally {
      primeiro.close();
    }

    assertThat(CacheLock.isHeldInThisProcess(pasta)).isFalse();
    final CacheLock depois = CacheLock.acquire(pasta, POUCA_ESPERA);
    assertThat(CacheLock.isHeldInThisProcess(pasta)).isTrue();
    depois.close();
  }

  @Test
  void umLockDeOutroProcessoERecusadoComoLockedDepoisDaEsperaTentandoDeNovo() throws Exception {
    final AtomicInteger tentativas = new AtomicInteger();
    final Duration espera = Duration.ofMillis(200);
    final long inicio = System.nanoTime();

    assertThatThrownBy(
            () ->
                CacheLock.acquire(
                    pasta,
                    espera,
                    canal -> {
                      tentativas.incrementAndGet();
                      return null;
                    }))
        .isInstanceOf(CacheUnavailableException.class)
        .extracting(failure -> ((CacheUnavailableException) failure).kind())
        .isEqualTo(CacheUnavailableException.Kind.LOCKED);

    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio))
        .isGreaterThanOrEqualTo(espera.toMillis());
    assertThat(tentativas.get())
        .as("espera tentando de novo, não uma tentativa só")
        .isGreaterThan(1);
    assertThat(CacheLock.isHeldInThisProcess(pasta)).as("a recusa sai do registro").isFalse();
    final CacheLock depois = CacheLock.acquire(pasta, POUCA_ESPERA);
    depois.close();
  }

  @Test
  void fecharDuasVezesNaoFazNadaNemSoltaQuemAdquiriuDepois() throws Exception {
    final CacheLock primeiro = CacheLock.acquire(pasta, POUCA_ESPERA);
    primeiro.close();
    final CacheLock segundo = CacheLock.acquire(pasta, POUCA_ESPERA);

    primeiro.close();

    assertThat(CacheLock.isHeldInThisProcess(pasta)).isTrue();
    segundo.close();
    assertThat(pasta.resolve(CacheLock.FILE_NAME))
        .as("apagá-lo deixaria o próximo processo travar outro inode")
        .isRegularFile();
  }

  @Test
  void umLockTomadoForaDoRegistroEDefeitoENaoEspera() throws Exception {
    assertThatThrownBy(
            () ->
                CacheLock.acquire(
                    pasta,
                    POUCA_ESPERA,
                    canal -> {
                      throw new OverlappingFileLockException();
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(OverlappingFileLockException.class);
    assertThat(CacheLock.isHeldInThisProcess(pasta)).isFalse();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umArquivoDeLockQueELinkERecusadoSemSeguirOLink() throws Exception {
    final Path alvo = temp.toRealPath().resolve("outro.lock");
    Files.createSymbolicLink(pasta.resolve(CacheLock.FILE_NAME), alvo);

    assertThatThrownBy(() -> CacheLock.acquire(pasta, POUCA_ESPERA))
        .isInstanceOf(IOException.class);
    assertThat(alvo).doesNotExist();
    assertThat(CacheLock.isHeldInThisProcess(pasta)).isFalse();
  }
}
