package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.NameFold;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SearchEscapeTest {

  private static final Set<ElementKind> AMBOS = Set.of(ElementKind.PACKAGE, ElementKind.TYPE);

  private static final int TETO = AnalysisConstants.SEARCH_RESULTS_MAX;

  private static final List<String> TIPOS_DE_SHOP =
      List.of(
          "Zorder",
          "aOrder",
          "Backorderlist",
          "Aorder",
          "OrderService",
          "Order",
          "Order$Item",
          "a_b",
          "axb",
          "ab",
          "50%",
          "50x",
          "a\\b",
          "Ação",
          "São");

  @TempDir private Path temp;

  private StructureCaches cache;

  private long shop;

  private long outra;

  @BeforeEach
  void semear() throws Exception {
    cache = StructureCaches.create(temp);
    final SyntheticAnalysis a = cache.analysis("an_search");
    final long pacote = a.addPackage("shop", NodeValues.PLAIN);
    final long orders = a.addPackage("shop.orders", NodeValues.PLAIN);
    a.addBarePackage("shop.preorder");
    long order = 0;
    for (final String nome : TIPOS_DE_SHOP) {
      final long chave = a.addType(pacote, nome, NodeValues.PLAIN);
      order = nome.equals("Order") ? chave : order;
    }
    a.addType(orders, "Cart", NodeValues.PLAIN);
    a.addExternalType("ext.Order");
    a.addMethod(order, "order");
    shop = a.write().pk();
    final SyntheticAnalysis segunda = cache.analysis("an_search_2");
    segunda.addType(segunda.addPackage("shop", NodeValues.PLAIN), "Order", NodeValues.PLAIN);
    outra = segunda.write().pk();
  }

  @AfterEach
  void fechar() throws SQLException {
    cache.close();
  }

  private List<SearchHit> busca(long analise, String consulta, Set<ElementKind> kinds, int limite)
      throws SQLException {
    final SearchTerm termo = new SearchTerm(NameFold.fold(consulta));
    return cache.read(c -> SearchQuery.search(c, analise, termo, kinds, limite));
  }

  private List<SearchHit> busca(String consulta) throws SQLException {
    return busca(shop, consulta, AMBOS, TETO);
  }

  private static SearchHit tipo(String nome) {
    final String canonico = nome.replace('$', '.');
    return new SearchHit(
        new CodeElementId("type:shop." + nome),
        ElementKind.TYPE,
        canonico,
        "shop." + canonico,
        new CodeElementId("pkg:shop"));
  }

  private static SearchHit pacote(String nome) {
    return new SearchHit(new CodeElementId("pkg:" + nome), ElementKind.PACKAGE, nome, nome, null);
  }

  @Test
  void orderRanqueiaPorFaixaTipoTamanhoEId() throws Exception {
    final List<SearchHit> esperados =
        List.of(
            tipo("Order"),
            tipo("OrderService"),
            pacote("shop.orders"),
            tipo("Aorder"),
            tipo("Zorder"),
            tipo("aOrder"),
            tipo("Backorderlist"),
            pacote("shop.preorder"));

    assertThat(busca("order")).isEqualTo(esperados);
    assertThat(busca("ORDER")).isEqualTo(esperados);
    assertThat(busca("order"))
        .extracting(hit -> hit.elementId().value())
        .doesNotContain("type:ext.Order", "method:shop.Order#order()");
  }

  @Test
  void oPacoteDeNomeIgualVemAntesDoTipoQueSoComecaPorEle() throws Exception {
    final SyntheticAnalysis faixa = cache.analysis("an_search_faixa");
    final long core = faixa.addPackage("app.core", NodeValues.PLAIN);
    faixa.addType(core, "CoreService", NodeValues.PLAIN);
    final long analise = faixa.write().pk();

    assertThat(busca(analise, "core", AMBOS, TETO))
        .containsExactly(
            new SearchHit(
                new CodeElementId("pkg:app.core"), ElementKind.PACKAGE, "core", "app.core", null),
            new SearchHit(
                new CodeElementId("type:app.core.CoreService"),
                ElementKind.TYPE,
                "CoreService",
                "app.core.CoreService",
                new CodeElementId("pkg:app.core")));
  }

  @Test
  void sublinhadoNaoEhCuringa() throws Exception {
    assertThat(busca("a_b")).containsExactly(tipo("a_b"));
  }

  @Test
  void porcentoNaoEhCuringa() throws Exception {
    assertThat(busca("50%")).containsExactly(tipo("50%"));
    assertThat(busca("50")).containsExactly(tipo("50%"), tipo("50x"));
  }

  @Test
  void barraInvertidaEhLiteral() throws Exception {
    assertThat(busca("a\\b")).containsExactly(tipo("a\\b"));
  }

  @Test
  void acentoECaixaSaoDobrados() throws Exception {
    assertThat(busca("acao")).containsExactly(tipo("Ação"));
    assertThat(busca("AÇÃO")).containsExactly(tipo("Ação"));
    assertThat(busca("Ação")).containsExactly(tipo("Ação"));
    assertThat(busca("SAO")).containsExactly(tipo("São"));
  }

  @Test
  void kindsRestringeAoTipoPedido() throws Exception {
    assertThat(busca(shop, "order", Set.of(ElementKind.PACKAGE), TETO))
        .containsExactly(pacote("shop.orders"), pacote("shop.preorder"));
    assertThat(busca(shop, "order", Set.of(ElementKind.TYPE), TETO))
        .containsExactly(
            tipo("Order"),
            tipo("OrderService"),
            tipo("Aorder"),
            tipo("Zorder"),
            tipo("aOrder"),
            tipo("Backorderlist"));
  }

  @Test
  void kindsVazioNaoAchaNada() throws Exception {
    assertThat(busca(shop, "order", Set.of(), TETO)).isEmpty();
  }

  @Test
  void limiteMantemOsPrimeirosPeloRank() throws Exception {
    assertThat(busca(shop, "order", AMBOS, 1)).containsExactly(tipo("Order"));
    assertThat(busca(shop, "order", AMBOS, 3))
        .containsExactly(tipo("Order"), tipo("OrderService"), pacote("shop.orders"));
  }

  @Test
  void oAchadoTrazORotuloEOPacoteDoTipo() throws Exception {
    assertThat(busca("item")).containsExactly(tipo("Order$Item"));
    assertThat(busca("cart"))
        .containsExactly(
            new SearchHit(
                new CodeElementId("type:shop.orders.Cart"),
                ElementKind.TYPE,
                "Cart",
                "shop.orders.Cart",
                new CodeElementId("pkg:shop.orders")));
  }

  @Test
  void aOutraAnaliseNaoVaza() throws Exception {
    assertThat(busca(outra, "order", AMBOS, TETO)).containsExactly(tipo("Order"));
    assertThat(busca(outra, "a_b", AMBOS, TETO)).isEmpty();
  }

  @Test
  void aBuscaNomeiaOSeuIndice() throws Exception {
    cache.write(
        c -> {
          try (PreparedStatement drop = c.prepareStatement("DROP INDEX idx_element_search")) {
            return drop.executeUpdate();
          }
        });

    assertThatThrownBy(() -> busca("order"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("idx_element_search");
  }
}
