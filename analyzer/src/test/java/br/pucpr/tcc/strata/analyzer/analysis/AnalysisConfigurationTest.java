package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class AnalysisConfigurationTest {

  private static final int TETO = AnalysisConstants.MAX_COMMITS_WALKED;

  private static final String ID_A = "sr_3gwc7d6d";
  private static final String ID_B = "sr_jc51sw4p";
  private static final String ID_C = "sr_sqbycfms";

  @Test
  void tudoOmitidoEOPadrao() {
    assertThat(AnalysisConfiguration.normalized(null, null, null, null, null))
        .isEqualTo(new AnalysisConfiguration(false, HistoryScope.ALL, null, null, false));
  }

  @Test
  void osValoresEnviadosFicamComoVieramQuandoJaEstaoNormalizados() {
    assertThat(
            AnalysisConfiguration.normalized(
                true, HistoryScope.LAST_N_COMMITS, 500L, List.of(ID_A, ID_C), true))
        .isEqualTo(
            new AnalysisConfiguration(
                true, HistoryScope.LAST_N_COMMITS, 500, List.of(ID_A, ID_C), true));
  }

  @Test
  void lastNSemNERecusadoComAFraseFixa() {
    assertThatThrownBy(
            () ->
                AnalysisConfiguration.normalized(
                    false, HistoryScope.LAST_N_COMMITS, null, null, false))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisConfiguration.LAST_N_WITHOUT_N);
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, -987_654L, Long.MIN_VALUE})
  void nAbaixoDeUmRecusadoComAFraseFixaSemEcoarOValor(long n) {
    assertThatThrownBy(
            () ->
                AnalysisConfiguration.normalized(
                    false, HistoryScope.LAST_N_COMMITS, n, null, false))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisConfiguration.N_BELOW_ONE)
        .hasMessageNotContaining(Long.toString(n));
  }

  @Test
  void listaVaziaRecusadaComAFraseFixa() {
    assertThatThrownBy(
            () -> AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, List.of(), false))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisConfiguration.EMPTY_SELECTION);
  }

  @Test
  void umElementoNuloRecusadoComAFraseFixa() {
    final List<String> comNulo = Arrays.asList(ID_A, null, ID_B);

    assertThatThrownBy(
            () -> AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, comNulo, false))
        .as("um [null] é payload inválido, não NullPointerException")
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisConfiguration.MALFORMED_SOURCE_ROOT_ID);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sr_3GWC7D6D",
        "sr_3gwc7d6",
        "sr_3gwc7d6dq",
        "sr_3gwc7d6i",
        "sr_3gwc7d6l",
        "sr_3gwc7d6o",
        "sr_3gwc7d6u",
        "rs_3gwc7d6d",
        " sr_3gwc7d6d",
        "",
        "/Users/alguem/segredo/src"
      })
  void umIdMalFormadoRecusadoComAFraseFixaSemEcoarOValor(String malFormado) {
    final List<String> ids = List.of(ID_A, malFormado);

    assertThatThrownBy(
            () -> AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, ids, false))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(AnalysisConfiguration.MALFORMED_SOURCE_ROOT_ID);
  }

  @Test
  void umIdMalFormadoRepetidoAindaERecusado() {
    final List<String> ids = List.of("sr_XXXXXXXX", "sr_XXXXXXXX");

    assertThatThrownBy(
            () -> AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, ids, false))
        .as("a deduplicação não pode engolir a recusa")
        .hasMessage(AnalysisConfiguration.MALFORMED_SOURCE_ROOT_ID);
  }

  static Stream<Arguments> cortes() {
    return Stream.of(
        Arguments.of(1L, 1),
        Arguments.of((long) TETO - 1, TETO - 1),
        Arguments.of((long) TETO, TETO),
        Arguments.of((long) TETO + 1, TETO),
        Arguments.of(1_000_000L, TETO),
        Arguments.of(3_000_000_000L, TETO),
        Arguments.of(Long.MAX_VALUE, TETO));
  }

  @ParameterizedTest
  @MethodSource("cortes")
  void nAcimaDoTetoECortadoNoTeto(long enviado, int guardado) {
    final AnalysisConfiguration configuracao =
        AnalysisConfiguration.normalized(false, HistoryScope.LAST_N_COMMITS, enviado, null, false);

    assertThat(configuracao.historyScopeCommits())
        .as("3 000 000 000 não cabe em um int: o Long do fio é o que deixa cortar em vez de falhar")
        .isEqualTo(guardado);
    assertThat(configuracao.historyScope()).isEqualTo(HistoryScope.LAST_N_COMMITS);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 5L, 200_000L, 3_000_000_000L})
  void allComNDescartaON(long n) {
    assertThat(AnalysisConfiguration.normalized(false, HistoryScope.ALL, n, null, false))
        .as("o NewAnalysis recusa ALL + N: a normalização o descarta antes")
        .isEqualTo(new AnalysisConfiguration(false, HistoryScope.ALL, null, null, false));
  }

  @Test
  void idsRepetidosFicamUmEmOrdemCrescente() {
    final List<String> enviados = new ArrayList<>(List.of(ID_C, ID_A, ID_C, ID_B, ID_A));

    final AnalysisConfiguration configuracao =
        AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, enviados, false);

    assertThat(configuracao.sourceRootIds()).containsExactly(ID_A, ID_B, ID_C);
    assertThat(enviados)
        .as("a lista do chamador não é tocada")
        .containsExactly(ID_C, ID_A, ID_C, ID_B, ID_A);
  }

  @Test
  void aSelecaoNormalizadaNaoMudaDepoisPorFora() {
    final List<String> enviados = new ArrayList<>(List.of(ID_B, ID_A));

    final AnalysisConfiguration configuracao =
        AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, enviados, false);
    enviados.clear();

    assertThat(configuracao.sourceRootIds()).containsExactly(ID_A, ID_B);
    assertThatThrownBy(() -> configuracao.sourceRootIds().add(ID_C))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void umaListaNaoNulaGuardaIncludeTestsComoVeio() {
    assertThat(
            AnalysisConfiguration.normalized(true, HistoryScope.ALL, null, List.of(ID_A), false)
                .includeTests())
        .as("com a lista explícita, includeTests só moldaria o nulo, e é guardado como chegou")
        .isTrue();
    assertThat(
            AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, List.of(ID_A), false)
                .includeTests())
        .isFalse();
    assertThat(
            AnalysisConfiguration.normalized(true, HistoryScope.ALL, null, List.of(ID_A), false)
                .sourceRootIds())
        .containsExactly(ID_A);
  }

  @Test
  void rebuildCacheEGuardadoComoVeio() {
    assertThat(AnalysisConfiguration.normalized(null, null, null, null, true).rebuildCache())
        .isTrue();
    assertThat(AnalysisConfiguration.normalized(null, null, null, null, false).rebuildCache())
        .isFalse();
  }

  static Stream<Arguments> formasNaoNormalizadas() {
    return Stream.of(
        Arguments.of(
            HistoryScope.LAST_N_COMMITS, TETO + 1, null, AnalysisConfiguration.N_ABOVE_CEILING),
        Arguments.of(HistoryScope.ALL, 5, null, AnalysisConfiguration.N_WITHOUT_LAST_N),
        Arguments.of(
            HistoryScope.ALL, null, List.of(ID_B, ID_A), AnalysisConfiguration.UNSORTED_SELECTION),
        Arguments.of(
            HistoryScope.ALL, null, List.of(ID_A, ID_A), AnalysisConfiguration.UNSORTED_SELECTION));
  }

  @ParameterizedTest
  @MethodSource("formasNaoNormalizadas")
  void oConstrutorRecusaUmaFormaNaoNormalizada(
      HistoryScope escopo, Integer n, List<String> ids, String frase) {
    assertThatThrownBy(() -> new AnalysisConfiguration(false, escopo, n, ids, false))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage(frase);
  }
}
