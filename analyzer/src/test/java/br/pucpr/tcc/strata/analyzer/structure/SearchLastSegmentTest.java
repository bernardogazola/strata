package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SearchLastSegmentTest {

  private static final Set<ElementKind> AMBOS = Set.of(ElementKind.PACKAGE, ElementKind.TYPE);

  @TempDir private Path temp;

  private StructureCaches cache;

  private long legacy;

  private long legacyDois;

  @BeforeEach
  void semear() throws Exception {
    cache = StructureCaches.create(temp);
    legacy = mundoLegacy("an_legacy", false);
    legacyDois = mundoLegacy("an_legacy_dois", true);
  }

  @AfterEach
  void fechar() throws SQLException {
    cache.close();
  }

  private long mundoLegacy(String analysisId, boolean comApiSpi) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long api = a.addPackage("br.legacy.api", NodeValues.PLAIN);
    final long core = a.addPackage("br.legacy.core", NodeValues.PLAIN);
    final long spi = a.addPackage("br.legacy.core.spi", NodeValues.PLAIN);
    a.addType(api, "SpiRegistry", NodeValues.PLAIN);
    a.addType(core, "Order", NodeValues.PLAIN);
    a.addType(core, "Order$Builder", NodeValues.PLAIN);
    a.addType(spi, "Plugin", NodeValues.PLAIN);
    if (comApiSpi) {
      a.addType(a.addPackage("br.legacy.api.spi", NodeValues.PLAIN), "Hook", NodeValues.PLAIN);
    }
    return a.write().pk();
  }

  private List<SearchHit> busca(long analise, String consulta) throws SQLException {
    final Optional<SearchTerm> termo = SearchTerm.of(consulta);
    if (termo.isEmpty()) {
      return List.of();
    }
    return cache.read(
        c ->
            SearchQuery.search(
                c, analise, termo.get(), AMBOS, AnalysisConstants.SEARCH_RESULTS_MAX));
  }

  private static SearchHit pacote(String nome, String rotulo) {
    return new SearchHit(new CodeElementId("pkg:" + nome), ElementKind.PACKAGE, rotulo, nome, null);
  }

  private static SearchHit tipo(String pacote, String nome, String rotulo) {
    final String canonico = pacote + "." + nome.replace('$', '.');
    return new SearchHit(
        new CodeElementId("type:" + pacote + "." + nome),
        ElementKind.TYPE,
        rotulo,
        canonico,
        new CodeElementId("pkg:" + pacote));
  }

  @Test
  void oRotuloDoPacoteAchaOPacoteNaFaixaExata() throws Exception {
    assertThat(busca(legacy, "core.spi"))
        .containsExactly(
            pacote("br.legacy.core.spi", "core.spi"),
            tipo("br.legacy.api", "SpiRegistry", "SpiRegistry"));
  }

  @Test
  void oRotuloDoAninhadoAchaOAninhado() throws Exception {
    final List<SearchHit> esperados =
        List.of(tipo("br.legacy.core", "Order$Builder", "Order.Builder"));

    assertThat(busca(legacy, "Order.Builder")).isEqualTo(esperados);
    assertThat(busca(legacy, "br.legacy.core.Order.Builder")).isEqualTo(esperados);
  }

  @Test
  void oPrefixoDoPontoNaoReordenaOsHomonimos() throws Exception {
    assertThat(busca(legacyDois, "core.spi"))
        .containsExactly(
            pacote("br.legacy.api.spi", "api.spi"),
            pacote("br.legacy.core.spi", "core.spi"),
            tipo("br.legacy.api", "SpiRegistry", "SpiRegistry"));
  }

  @Test
  void umUltimoSegmentoVazioNaoAchaNada() throws Exception {
    assertThat(busca(legacy, "a.")).isEmpty();
    assertThat(busca(legacy, ".")).isEmpty();
    assertThat(busca(legacy, "core.")).isEmpty();
  }
}
