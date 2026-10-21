package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class StageCountersTest {

  @ParameterizedTest
  @ValueSource(strings = {"files", "x", "resolvedRefs", "dropLocalTarget", "a1B2c3"})
  void aceitaChaveCamelCase(String chave) {
    assertThat(StageCounters.of(Map.of(chave, 0L)).values()).containsOnlyKeys(chave);
  }

  @Test
  void aceitaAChaveDeSessentaEQuatroCaracteres() {
    final String chave = "a".repeat(64);

    assertThat(StageCounters.of(Map.of(chave, 1L)).values()).containsOnlyKeys(chave);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Files",
        "1files",
        "_files",
        "drop_local",
        "drop-local",
        "drop.local",
        "drop local",
        "contagemÇ",
        "files\n"
      })
  void recusaChaveForaDaGramatica(String chave) {
    assertThatThrownBy(() -> StageCounters.of(Map.of(chave, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recusaAChaveDeSessentaECincoCaracteres() {
    assertThatThrownBy(() -> StageCounters.of(Map.of("a".repeat(65), 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recusaChaveNula() {
    final Map<String, Long> comNula = new HashMap<>();
    comNula.put(null, 1L);

    assertThatThrownBy(() -> StageCounters.of(comNula))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recusaValorNegativoOuNulo() {
    final Map<String, Long> comNulo = new HashMap<>();
    comNulo.put("files", null);

    assertThatThrownBy(() -> StageCounters.of(Map.of("files", -1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StageCounters.of(comNulo))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCopiaEOrdenadaPorChaveIndependenteDaOrdemDeEntrada() {
    final Map<String, Long> foraDeOrdem = new LinkedHashMap<>();
    foraDeOrdem.put("types", 1284L);
    foraDeOrdem.put("files", 3902L);
    foraDeOrdem.put("inferredRefs", 7L);

    final StageCounters contadores = StageCounters.of(foraDeOrdem);

    assertThat(contadores.values().keySet()).containsExactly("files", "inferredRefs", "types");
  }

  @Test
  void aCopiaEImutavelEDefensiva() {
    final Map<String, Long> origem = new HashMap<>(Map.of("files", 1L));
    final StageCounters contadores = StageCounters.of(origem);

    origem.put("types", 2L);

    assertThat(contadores.values()).containsOnlyKeys("files");
    assertThatThrownBy(() -> contadores.values().put("types", 2L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void oJsonCanonicoEOTextoDoBanco() {
    final Map<String, Long> foraDeOrdem = new LinkedHashMap<>();
    foraDeOrdem.put("types", 1284L);
    foraDeOrdem.put("files", 3902L);
    foraDeOrdem.put("droppedDeclaredTargets", 0L);
    foraDeOrdem.put("elementIdsTooLong", 9_007_199_254_740_993L);

    assertThat(StageCounters.of(foraDeOrdem).toJson())
        .isEqualTo(
            "{\"droppedDeclaredTargets\":0,\"elementIdsTooLong\":9007199254740993,"
                + "\"files\":3902,\"types\":1284}");
  }

  @Test
  void nenhumContadorEOMapaVazio() {
    assertThat(StageCounters.none().values()).isEmpty();
    assertThat(StageCounters.none().toJson()).isEqualTo("{}");
    assertThat(StageCounters.of(Map.of())).isEqualTo(StageCounters.none());
  }

  @Test
  void doisValoresComOsMesmosContadoresSaoIguais() {
    final Map<String, Long> umaOrdem = new LinkedHashMap<>();
    umaOrdem.put("b", 2L);
    umaOrdem.put("a", 1L);

    assertThat(StageCounters.of(umaOrdem)).isEqualTo(StageCounters.of(Map.of("a", 1L, "b", 2L)));
  }
}
