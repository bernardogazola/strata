package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class AnalysisIdsTest {

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final String BASE32_DO_BIGINTEGER = "0123456789abcdefghijklmnopqrstuv";

  private static final int CARACTERES = 26;

  private static final int CARACTERES_DO_RELOGIO = 10;

  private static final int BITS_ALEATORIOS = 80;

  private static final int BYTES_ALEATORIOS = BITS_ALEATORIOS / Byte.SIZE;

  private static final long MAIOR_MILISSEGUNDO = (1L << 48) - 1;

  private static final long T = 1_761_055_200_000L;

  @Test
  void oPrimeiroIdEOOraculoDoRelogioEDoAleatorio() {
    final AnalysisIds ids = new AnalysisIds(fixo(T), new ByteRepetido((byte) 0x5A));

    assertThat(ids.get().value()).isEqualTo(oraculo(T, repetido((byte) 0x5A)));
  }

  @Test
  void osVetoresDeBordaDaoZerosESetesZs() {
    assertThat(new AnalysisIds(fixo(0), new ByteRepetido((byte) 0)).get().value())
        .isEqualTo("an_00000000000000000000000000");
    assertThat(
            new AnalysisIds(fixo(MAIOR_MILISSEGUNDO), new ByteRepetido((byte) 0xFF)).get().value())
        .as("(2^48 − 1, 2^80 − 1): os dois bits altos do primeiro caractere ficam zero")
        .isEqualTo("an_7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
  }

  @Test
  void milVetoresSorteadosBatemComOOraculo() {
    final SplittableRandom sorteio = new SplittableRandom(2027);
    for (int vetor = 0; vetor < 1_000; vetor++) {
      final long millis = sorteio.nextLong(MAIOR_MILISSEGUNDO + 1);
      final byte b = (byte) sorteio.nextInt(256);

      final String id = new AnalysisIds(fixo(millis), new ByteRepetido(b)).get().value();

      assertThat(id).as("millis=%d byte=%02x", millis, b).isEqualTo(oraculo(millis, repetido(b)));
    }
  }

  @Test
  void todoIdSegueAGramaticaEOPrefixoDoRelogioDecodificaParaORelogio() {
    final SplittableRandom sorteio = new SplittableRandom(11);
    for (int vetor = 0; vetor < 200; vetor++) {
      final long millis = sorteio.nextLong(MAIOR_MILISSEGUNDO + 1);
      final AnalysisIds ids = new AnalysisIds(fixo(millis), new SplittableRandom(vetor));

      final String id = ids.get().value();

      assertThat(id).matches("^an_[0-7][0-9A-HJKMNP-TV-Z]{25}$");
      assertThat(decodificar(id.substring(3, 3 + CARACTERES_DO_RELOGIO)))
          .as("os 10 primeiros caracteres são os 48 bits do relógio")
          .isEqualTo(BigInteger.valueOf(millis));
    }
  }

  @Test
  void dezMilIdsNoMesmoMilissegundoSobemDeUmEmUm() {
    final AnalysisIds ids = new AnalysisIds(fixo(T), new SplittableRandom(5));
    BigInteger anterior = valor(ids.get());

    for (int i = 1; i < 10_000; i++) {
      final AnalysisId id = ids.get();
      final BigInteger atual = valor(id);

      assertThat(atual)
          .as("id %d: os 80 bits aleatórios do anterior somados de 1, como um número só", i)
          .isEqualTo(anterior.add(BigInteger.ONE));
      assertThat(atual.shiftRight(BITS_ALEATORIOS)).isEqualTo(BigInteger.valueOf(T));
      anterior = atual;
    }
  }

  @Test
  void oTextoTambemCrescePorqueOAlfabetoCrockfordEstaEmOrdemAscii() {
    final AnalysisIds ids = new AnalysisIds(fixo(T), new SplittableRandom(6));
    String anterior = ids.get().value();

    for (int i = 1; i < 2_000; i++) {
      final String atual = ids.get().value();

      assertThat(atual).isGreaterThan(anterior);
      anterior = atual;
    }
  }

  @Test
  void quandoORelogioVoltaOIdSeguinteContinuaMaior() {
    final AtomicLong relogio = new AtomicLong(T);
    final AnalysisIds ids = new AnalysisIds(relogio(relogio), new ByteRepetido((byte) 0));
    final AnalysisId primeiro = ids.get();

    relogio.set(T - 5);
    final AnalysisId depoisDeVoltar = ids.get();

    assertThat(primeiro.value()).isEqualTo(oraculo(T, repetido((byte) 0)));
    assertThat(valor(depoisDeVoltar))
        .as("o relógio voltou 5 ms: fica o milissegundo do último e o aleatório soma 1")
        .isEqualTo(valor(primeiro).add(BigInteger.ONE));

    relogio.set(T + 10);
    final AnalysisId depoisDeAvancar = ids.get();
    assertThat(depoisDeAvancar.value())
        .as("o relógio passou do último: milissegundo novo e aleatório sorteado de novo")
        .isEqualTo(oraculo(T + 10, repetido((byte) 0)));
    assertThat(valor(depoisDeAvancar)).isGreaterThan(valor(depoisDeVoltar));
  }

  @Test
  void noEstouroDoAleatorioOMilissegundoAvancaUmESorteiaDeNovo() {
    final AnalysisIds ids = new AnalysisIds(fixo(T), new ByteRepetido((byte) 0xFF));
    final AnalysisId noTeto = ids.get();

    final AnalysisId depois = ids.get();

    assertThat(noTeto.value()).isEqualTo(oraculo(T, repetido((byte) 0xFF)));
    assertThat(depois.value())
        .as("2^80 − 1 + 1 estoura: +1 ms, e o sorteio novo dá 2^80 − 1 outra vez")
        .isEqualTo(oraculo(T + 1, repetido((byte) 0xFF)));
    assertThat(valor(depois)).isGreaterThan(valor(noTeto));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void oitoThreadsNuncaRecebemOMesmoId() throws Exception {
    final AnalysisIds ids = new AnalysisIds(fixo(T), new SplittableRandom(8));
    final int threads = 8;
    final int porThread = 1_000;
    final CountDownLatch largada = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    final Set<String> vistos = new HashSet<>();
    try {
      final List<Future<List<String>>> lotes = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        lotes.add(
            executor.submit(
                () -> {
                  largada.await();
                  final List<String> meus = new ArrayList<>();
                  for (int i = 0; i < porThread; i++) {
                    meus.add(ids.get().value());
                  }
                  return meus;
                }));
      }
      largada.countDown();
      for (final Future<List<String>> lote : lotes) {
        vistos.addAll(lote.get(20, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(vistos).hasSize(threads * porThread);
  }

  @Test
  void oCunhadorDeProducaoUsaORelogioDeParede() {
    final long antes = System.currentTimeMillis();

    final AnalysisId id = AnalysisIds.system().get();

    final long depois = System.currentTimeMillis();
    assertThat(id.value()).matches("^an_[0-7][0-9A-HJKMNP-TV-Z]{25}$");
    assertThat(decodificar(id.value().substring(3, 3 + CARACTERES_DO_RELOGIO)).longValueExact())
        .isBetween(antes, depois);
  }

  private static String oraculo(long millis, byte[] aleatorio) {
    final byte[] bytes =
        ByteBuffer.allocate(16)
            .put(Arrays.copyOfRange(ByteBuffer.allocate(8).putLong(millis).array(), 2, 8))
            .put(aleatorio)
            .array();
    final String base32 = new BigInteger(1, bytes).toString(32);
    final StringBuilder traduzido = new StringBuilder("an_");
    traduzido.append("0".repeat(CARACTERES - base32.length()));
    for (final char c : base32.toCharArray()) {
      traduzido.append(CROCKFORD.charAt(BASE32_DO_BIGINTEGER.indexOf(c)));
    }
    return traduzido.toString();
  }

  private static BigInteger valor(AnalysisId id) {
    return decodificar(id.value().substring(3));
  }

  private static BigInteger decodificar(String crockford) {
    final StringBuilder base32 = new StringBuilder();
    for (final char c : crockford.toCharArray()) {
      final int digito = CROCKFORD.indexOf(c);
      assertThat(digito).as("'%s' fora do alfabeto Crockford", c).isNotNegative();
      base32.append(BASE32_DO_BIGINTEGER.charAt(digito));
    }
    return new BigInteger(base32.toString(), 32);
  }

  private static byte[] repetido(byte b) {
    final byte[] bytes = new byte[BYTES_ALEATORIOS];
    Arrays.fill(bytes, b);
    return bytes;
  }

  private static InstantSource fixo(long millis) {
    return InstantSource.fixed(Instant.ofEpochMilli(millis));
  }

  private static InstantSource relogio(AtomicLong millis) {
    return () -> Instant.ofEpochMilli(millis.get());
  }

  private record ByteRepetido(byte b) implements RandomGenerator {

    @Override
    public long nextLong() {
      long repetido = 0;
      for (int i = 0; i < Long.BYTES; i++) {
        repetido = (repetido << Byte.SIZE) | (b & 0xFFL);
      }
      return repetido;
    }
  }
}
