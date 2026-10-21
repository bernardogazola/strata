package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConfigHashTest {

  private static final String JSON_PADRAO =
      "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":false,"
          + "\"sourceRootIds\":null}";

  private static final String HASH_PADRAO =
      "c8ddc5aec20c88456feb94818eb0c8fe6c9838069031742b38a6b5dc4edffb1d";

  private static final String JSON_ULTIMOS_N =
      "{\"historyScope\":\"LAST_N_COMMITS\",\"historyScopeCommits\":200000,\"includeTests\":true,"
          + "\"sourceRootIds\":[\"sr_3gwc7d6d\",\"sr_jc51sw4p\",\"sr_sqbycfms\"]}";

  private static final String HASH_ULTIMOS_N =
      "d7099feafade1ad695bec2b756fb1a93dfbcb24c3973a5f426e15ee4ec9e39ed";

  private static final List<String> IDS_DO_MULTI_MODULE =
      List.of("sr_3gwc7d6d", "sr_jc51sw4p", "sr_sqbycfms");

  private static final AnalysisConfiguration PADRAO =
      new AnalysisConfiguration(false, HistoryScope.ALL, null, null, false);

  private static final AnalysisConfiguration ULTIMOS_N =
      new AnalysisConfiguration(
          true, HistoryScope.LAST_N_COMMITS, 200_000, IDS_DO_MULTI_MODULE, false);

  @Test
  void oPrimeiroVetorBateNoTextoENoHash() {
    assertThat(ConfigHash.canonicalJson(PADRAO)).isEqualTo(JSON_PADRAO);
    assertThat(ConfigHash.of(PADRAO)).isEqualTo(HASH_PADRAO);
  }

  @Test
  void oSegundoVetorBateNoTextoENoHash() {
    assertThat(ConfigHash.canonicalJson(ULTIMOS_N)).isEqualTo(JSON_ULTIMOS_N);
    assertThat(ConfigHash.of(ULTIMOS_N)).isEqualTo(HASH_ULTIMOS_N);
  }

  @Test
  void oPedidoDoSegundoVetorNormalizadoDaOMesmoTextoEOMesmoHash() {
    final List<String> foraDeOrdemComRepetido = new ArrayList<>();
    foraDeOrdemComRepetido.add("sr_sqbycfms");
    foraDeOrdemComRepetido.add("sr_3gwc7d6d");
    foraDeOrdemComRepetido.add("sr_jc51sw4p");
    foraDeOrdemComRepetido.add("sr_3gwc7d6d");

    final AnalysisConfiguration normalizada =
        AnalysisConfiguration.normalized(
            true, HistoryScope.LAST_N_COMMITS, 1_000_000L, foraDeOrdemComRepetido, false);

    assertThat(ConfigHash.canonicalJson(normalizada)).isEqualTo(JSON_ULTIMOS_N);
    assertThat(ConfigHash.of(normalizada)).isEqualTo(HASH_ULTIMOS_N);
  }

  @Test
  void oPedidoVazioNormalizadoEOPrimeiroVetor() {
    final AnalysisConfiguration normalizada =
        AnalysisConfiguration.normalized(null, null, null, null, null);

    assertThat(ConfigHash.canonicalJson(normalizada)).isEqualTo(JSON_PADRAO);
    assertThat(ConfigHash.of(normalizada)).isEqualTo(HASH_PADRAO);
  }

  @Test
  void oHashEOSha256DosBytesUtf8DoTextoEmHexaMinusculo() throws Exception {
    for (final AnalysisConfiguration configuracao : List.of(PADRAO, ULTIMOS_N)) {
      final byte[] texto = ConfigHash.canonicalJson(configuracao).getBytes(StandardCharsets.UTF_8);

      assertThat(ConfigHash.of(configuracao))
          .matches("^[0-9a-f]{64}$")
          .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto)));
    }
  }

  @Test
  void oTextoTemExatamenteAsQuatroChavesEmOrdemESemRebuildCache() throws Exception {
    final List<String> chaves = new ArrayList<>();
    new ObjectMapper()
        .readTree(ConfigHash.canonicalJson(ULTIMOS_N))
        .fieldNames()
        .forEachRemaining(chaves::add);

    assertThat(chaves)
        .containsExactly("historyScope", "historyScopeCommits", "includeTests", "sourceRootIds");
    assertThat(ConfigHash.canonicalJson(ULTIMOS_N)).doesNotContain(" ").doesNotContain("\n");
  }

  @Test
  void rebuildCacheNaoMudaNemOTextoNemOHash() {
    final AnalysisConfiguration reconstruir =
        new AnalysisConfiguration(
            true, HistoryScope.LAST_N_COMMITS, 200_000, IDS_DO_MULTI_MODULE, true);

    assertThat(ConfigHash.canonicalJson(reconstruir)).isEqualTo(JSON_ULTIMOS_N);
    assertThat(ConfigHash.of(reconstruir)).isEqualTo(HASH_ULTIMOS_N);
  }

  @Test
  void aOrdemEAsRepeticoesDosIdsNaoMudamOHash() {
    final AnalysisConfiguration ordenada =
        AnalysisConfiguration.normalized(
            false, HistoryScope.ALL, null, List.of("sr_jc51sw4p", "sr_sqbycfms"), false);
    final AnalysisConfiguration embaralhada =
        AnalysisConfiguration.normalized(
            false,
            HistoryScope.ALL,
            null,
            List.of("sr_sqbycfms", "sr_jc51sw4p", "sr_sqbycfms"),
            false);

    assertThat(ConfigHash.canonicalJson(embaralhada)).isEqualTo(ConfigHash.canonicalJson(ordenada));
    assertThat(ConfigHash.of(embaralhada)).isEqualTo(ConfigHash.of(ordenada));
  }

  @Test
  void nuloEOPadraoCruEAListaVaziaNaoExiste() {
    assertThatThrownBy(
            () -> AnalysisConfiguration.normalized(false, HistoryScope.ALL, null, List.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnalysisConfiguration(false, HistoryScope.ALL, null, List.of(), false))
        .isInstanceOf(IllegalArgumentException.class);

    final AnalysisConfiguration todasExplicitas =
        new AnalysisConfiguration(false, HistoryScope.ALL, null, IDS_DO_MULTI_MODULE, false);

    assertThat(ConfigHash.of(PADRAO)).isEqualTo(HASH_PADRAO);
    assertThat(ConfigHash.canonicalJson(todasExplicitas))
        .isEqualTo(
            "{\"historyScope\":\"ALL\",\"historyScopeCommits\":null,\"includeTests\":false,"
                + "\"sourceRootIds\":[\"sr_3gwc7d6d\",\"sr_jc51sw4p\",\"sr_sqbycfms\"]}");
    assertThat(ConfigHash.of(todasExplicitas)).isNotEqualTo(HASH_PADRAO);
  }
}
