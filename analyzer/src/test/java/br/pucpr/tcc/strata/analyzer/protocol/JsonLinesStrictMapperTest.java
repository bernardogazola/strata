package br.pucpr.tcc.strata.analyzer.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class JsonLinesStrictMapperTest {

  record Inicio(
      Boolean includeTests,
      HistoryScope historyScope,
      Long historyScopeCommits,
      List<String> sourceRootIds,
      Boolean rebuildCache) {}

  record Referencia(String analysisId) {}

  private static final String VALIDO =
      "{\"includeTests\":true,\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":500,"
          + "\"sourceRootIds\":[\"sr_3gwc7d6d\"],\"rebuildCache\":false}";

  private final ObjectMapper mapper = JsonLines.strictInboundMapper();

  @Test
  void umNumeroNoLugarDoEnumERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"historyScope\":1}", Inicio.class))
        .as("medido: o ordinal 1 virava LAST_N_COMMITS")
        .isInstanceOf(DatabindException.class);
  }

  @Test
  void umNumeroNoLugarDoBooleanoERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"includeTests\":1}", Inicio.class))
        .as("medido: 1 virava true")
        .isInstanceOf(DatabindException.class);
    assertThatThrownBy(() -> mapper.readValue("{\"rebuildCache\":0}", Inicio.class))
        .isInstanceOf(DatabindException.class);
  }

  @Test
  void umFracionarioNoLugarDoInteiroERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"historyScopeCommits\":1.5}", Inicio.class))
        .as("medido: 1.5 virava 1")
        .isInstanceOf(DatabindException.class);
  }

  @Test
  void umNumeroDentroDaListaDeTextoERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"sourceRootIds\":[1]}", Inicio.class))
        .as("medido: [1] virava [\"1\"]")
        .isInstanceOf(DatabindException.class);
  }

  @Test
  void umNumeroNoLugarDoAnalysisIdERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"analysisId\":5}", Referencia.class))
        .as("medido: 5 virava \"5\"")
        .isInstanceOf(DatabindException.class);
  }

  @Test
  void umNumeroEscritoComoTextoNoLugarDoEnumERecusado() {
    assertThatThrownBy(() -> mapper.readValue("{\"historyScope\":\"1\"}", Inicio.class))
        .as(
            "medido: o texto \"1\" também virava o ordinal 1; FAIL_ON_NUMBERS_FOR_ENUMS fecha os dois")
        .isInstanceOf(DatabindException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"sourceRootIds\":[true]}",
        "{\"sourceRootIds\":[1.5]}",
        "{\"analysisId\":true}",
        "{\"analysisId\":5.5}"
      })
  void umBooleanoOuFracionarioNoLugarDeTextoERecusado(String carga) {
    final Class<?> alvo = carga.contains("analysisId") ? Referencia.class : Inicio.class;

    assertThatThrownBy(() -> mapper.readValue(carga, alvo)).isInstanceOf(DatabindException.class);
  }

  @Test
  void aCargaValidaCompletaContinuaAceita() throws Exception {
    assertThat(mapper.readValue(VALIDO, Inicio.class))
        .isEqualTo(
            new Inicio(true, HistoryScope.LAST_N_COMMITS, 500L, List.of("sr_3gwc7d6d"), false));
    assertThat(
            mapper.readValue(
                "{\"analysisId\":\"an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W\"}", Referencia.class))
        .isEqualTo(new Referencia("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W"));
  }

  @Test
  void aCargaVaziaEOsNulosContinuamAceitos() throws Exception {
    assertThat(mapper.readValue("{}", Inicio.class))
        .as("uma chave omitida quer dizer o padrão: o DTO em caixa recebe nulo")
        .isEqualTo(new Inicio(null, null, null, null, null));
    assertThat(
            mapper.readValue(
                "{\"includeTests\":null,\"historyScope\":null,\"historyScopeCommits\":null,"
                    + "\"sourceRootIds\":null,\"rebuildCache\":null}",
                Inicio.class))
        .isEqualTo(new Inicio(null, null, null, null, null));
  }

  @Test
  void tresBilhoesCabemNoLongParaSeremCortadosDepois() throws Exception {
    assertThat(
            mapper
                .readValue("{\"historyScopeCommits\":3000000000}", Inicio.class)
                .historyScopeCommits())
        .as("o N do fio é Long, para que 3e9 seja cortado na normalização em vez de falhar aqui")
        .isEqualTo(3_000_000_000L);
  }

  @Test
  void osNegativosEOZeroPassamAquiParaANormalizacaoRecusar() throws Exception {
    assertThat(mapper.readValue("{\"historyScopeCommits\":0}", Inicio.class).historyScopeCommits())
        .isZero();
    assertThat(mapper.readValue("{\"historyScopeCommits\":-5}", Inicio.class).historyScopeCommits())
        .isEqualTo(-5L);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"includeTests\":\"true\"}",
        "{\"historyScopeCommits\":\"500\"}",
        "{\"includeTests\":true,\"desconhecido\":1}"
      })
  void asRecusasQueJaExistiamContinuam(String carga) {
    assertThatThrownBy(() -> mapper.readValue(carga, Inicio.class))
        .as("texto no lugar de escalar e chave desconhecida continuam recusados")
        .isInstanceOf(DatabindException.class);
  }
}
