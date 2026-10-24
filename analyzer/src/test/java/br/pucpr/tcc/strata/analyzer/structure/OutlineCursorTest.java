package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.APP_PACKAGES;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.PACKAGE_BUDGET;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.TYPE_BUDGET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OutlineCursorTest {

  private static final String Z_LARGO = Character.toString(0xFF3A);

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final String NOME_LONGO = "B" + "x".repeat(3_990);

  private static final int MEIO_MILHAR = 500;

  @TempDir private Path temp;

  private StructureCaches cache;

  private final Map<String, Long> pk = new HashMap<>();

  private long outline;

  private long outra;

  private long pacoteDaOutra;

  @BeforeEach
  void semear() throws Exception {
    cache = StructureCaches.create(temp);
    outline = mundoOutline();
    final SyntheticAnalysis segunda = cache.analysis("an_outline_2");
    pacoteDaOutra = segunda.addPackage("q", NodeValues.PLAIN);
    segunda.addType(pacoteDaOutra, "Q", NodeValues.PLAIN);
    outra = segunda.write().pk();
  }

  @AfterEach
  void fechar() throws SQLException {
    cache.close();
  }

  private long mundoOutline() throws SQLException {
    final SyntheticAnalysis a = cache.analysis("an_outline");
    final List<String> pacotes =
        List.of(
            "p." + Z_MATEMATICO, "p." + Z_LARGO, "p.b", "p.a", "other", "long", "kilo", "big", "");
    for (final String nome : pacotes) {
      pk.put("pkg:" + nome, a.addPackage(nome, NodeValues.PLAIN));
    }
    pk.put("pkg:p.empty", a.addBarePackage("p.empty"));
    tipo(a, "", "Main");
    for (int k = 0; k < 1_201; k++) {
      tipo(a, "big", "T%04d".formatted(k * 7_919 % 1_201));
    }
    for (int k = 999; k >= 0; k--) {
      tipo(a, "kilo", "K%04d".formatted(k));
    }
    tipo(a, "long", "C");
    tipo(a, "long", NOME_LONGO);
    tipo(a, "long", "A");
    tipo(a, "other", "X");
    final long order = tipo(a, "p.a", "Order");
    tipo(a, "p.a", "Order$Builder");
    pk.put("method:p.a.Order#total()", a.addMethod(order, "total"));
    tipo(a, "p.b", "Y");
    tipo(a, "p." + Z_LARGO, "Z1");
    tipo(a, "p." + Z_MATEMATICO, "Z2");
    pk.put("type:java.util.List", a.addExternalType("java.util.List"));
    return a.write().pk();
  }

  private long tipo(SyntheticAnalysis a, String pacote, String nome) {
    final String id = "type:" + (pacote.isEmpty() ? "" : pacote + ".") + nome;
    final long chave = a.addType(pk.get("pkg:" + pacote), nome, NodeValues.PLAIN);
    pk.put(id, chave);
    return chave;
  }

  private OutlinePage pagina(long analise, String pai, OutlineCursor depois, int limite)
      throws SQLException {
    final CodeElementId parent = pai == null ? null : new CodeElementId(pai);
    return cache.read(c -> OutlineQuery.page(c, analise, parent, depois, limite));
  }

  private static OutlineItem pacote(String nome, int filhos) {
    return new OutlineItem(new CodeElementId("pkg:" + nome), ElementKind.PACKAGE, nome, filhos);
  }

  private static OutlineItem tipoDe(String pacote, String nome, String rotulo) {
    return new OutlineItem(
        new CodeElementId("type:" + pacote + "." + nome), ElementKind.TYPE, rotulo, 0);
  }

  private static List<OutlineItem> faixa(int de, int ate, IntFunction<OutlineItem> item) {
    return IntStream.range(de, ate).mapToObj(item).toList();
  }

  private OutlineCursor cursorDe(String id) {
    return new OutlineCursor(pk.get(id));
  }

  @Test
  void aRaizEhOConjuntoDaFase30EmOrdemBinariaComOPadraoPrimeiro() throws Exception {
    final OutlinePage esperada =
        new OutlinePage(
            List.of(
                pacote("", 1),
                pacote("big", 1_201),
                pacote("kilo", 1_000),
                pacote("long", 3),
                pacote("other", 1),
                pacote("p.a", 2),
                pacote("p.b", 1),
                pacote("p." + Z_LARGO, 1),
                pacote("p." + Z_MATEMATICO, 1)),
            null);

    final OutlinePage pagina = pagina(outline, null, null, MEIO_MILHAR);

    assertThat(pagina).isEqualTo(esperada);
  }

  @Test
  void aRaizEmPaginasDeQuatroCarregaAChaveDaUltimaLinha() throws Exception {
    final List<OutlineItem> um =
        List.of(pacote("", 1), pacote("big", 1_201), pacote("kilo", 1_000), pacote("long", 3));
    final List<OutlineItem> dois =
        List.of(pacote("other", 1), pacote("p.a", 2), pacote("p.b", 1), pacote("p." + Z_LARGO, 1));
    final List<OutlineItem> tres = List.of(pacote("p." + Z_MATEMATICO, 1));

    final OutlinePage primeira = pagina(outline, null, null, 4);
    final OutlinePage segunda = pagina(outline, null, primeira.next(), 4);
    final OutlinePage terceira = pagina(outline, null, segunda.next(), 4);

    assertThat(primeira).isEqualTo(new OutlinePage(um, cursorDe("pkg:long")));
    assertThat(segunda).isEqualTo(new OutlinePage(dois, cursorDe("pkg:p." + Z_LARGO)));
    assertThat(terceira).isEqualTo(new OutlinePage(tres, null));
  }

  @Test
  void aRaizSegueTodoPacoteMesmoQuandoONivelUmAgrega() throws Exception {
    final List<OutlineItem> esperados = new ArrayList<>();
    for (final String nome : APP_PACKAGES) {
      final String rotulo = nome.substring("app.".length());
      final int filhos = nome.equals("app.util.u000") ? 2 : 1;
      esperados.add(
          new OutlineItem(new CodeElementId("pkg:" + nome), ElementKind.PACKAGE, rotulo, filhos));
    }
    final long app = cache.appWorld("an_app").pk();

    final OutlinePage pagina = pagina(app, null, null, MEIO_MILHAR);
    final Slice nivelUm =
        cache.read(c -> GraphSliceQuery.slice(c, app, GraphLevel.PACKAGE, null, PACKAGE_BUDGET));

    assertThat(pagina).isEqualTo(new OutlinePage(esperados, null));
    assertThat(nivelUm.aggregated()).isTrue();
    assertThat(nivelUm.nodes()).hasSize(153);
  }

  @Test
  void milDuzentosEUmTiposEmTresPaginasSemBuracoNemRepeticao() throws Exception {
    final IntFunction<OutlineItem> t =
        k -> tipoDe("big", "T%04d".formatted(k), "T%04d".formatted(k));
    final OutlinePage um = new OutlinePage(faixa(0, 500, t), cursorDe("type:big.T0499"));
    final OutlinePage dois = new OutlinePage(faixa(500, 1_000, t), cursorDe("type:big.T0999"));
    final OutlinePage tres = new OutlinePage(faixa(1_000, 1_201, t), null);

    final OutlinePage primeira = pagina(outline, "pkg:big", null, MEIO_MILHAR);
    final OutlinePage segunda = pagina(outline, "pkg:big", primeira.next(), MEIO_MILHAR);
    final OutlinePage terceira = pagina(outline, "pkg:big", segunda.next(), MEIO_MILHAR);

    assertThat(List.of(primeira, segunda, terceira)).containsExactly(um, dois, tres);
    final List<OutlineItem> todas = new ArrayList<>(primeira.items());
    todas.addAll(segunda.items());
    todas.addAll(terceira.items());
    assertThat(todas).hasSize(1_201).doesNotHaveDuplicates().isEqualTo(faixa(0, 1_201, t));
  }

  @Test
  void milTiposSaoExatamenteDuasPaginas() throws Exception {
    final IntFunction<OutlineItem> k =
        i -> tipoDe("kilo", "K%04d".formatted(i), "K%04d".formatted(i));
    final OutlinePage um = new OutlinePage(faixa(0, 500, k), cursorDe("type:kilo.K0499"));
    final OutlinePage dois = new OutlinePage(faixa(500, 1_000, k), null);

    final OutlinePage primeira = pagina(outline, "pkg:kilo", null, MEIO_MILHAR);
    final OutlinePage segunda = pagina(outline, "pkg:kilo", primeira.next(), MEIO_MILHAR);

    assertThat(primeira).isEqualTo(um);
    assertThat(segunda).isEqualTo(dois);
  }

  @Test
  void oCursorDeUmIdDeQuatroMilBytesTemDozeCaracteres() throws Exception {
    final String idLongo = "type:long." + NOME_LONGO;
    assertThat(idLongo.getBytes(StandardCharsets.UTF_8).length)
        .isEqualTo(4_001)
        .isLessThanOrEqualTo(AnalysisConstants.MAX_ELEMENT_ID_BYTES);
    final String rotuloCortado = "B" + "x".repeat(AnalysisConstants.MAX_LABEL_CHARS - 1);
    final OutlinePage um =
        new OutlinePage(List.of(tipoDe("long", "A", "A")), cursorDe("type:long.A"));
    final OutlinePage dois =
        new OutlinePage(
            List.of(
                new OutlineItem(new CodeElementId(idLongo), ElementKind.TYPE, rotuloCortado, 0)),
            cursorDe(idLongo));
    final OutlinePage tres = new OutlinePage(List.of(tipoDe("long", "C", "C")), null);

    final OutlinePage primeira = pagina(outline, "pkg:long", null, 1);
    final OutlinePage segunda = pagina(outline, "pkg:long", primeira.next(), 1);
    final OutlinePage terceira = pagina(outline, "pkg:long", segunda.next(), 1);

    assertThat(List.of(primeira, segunda, terceira)).containsExactly(um, dois, tres);
    assertThat(segunda.next().encode())
        .hasSize(12)
        .matches("[A-Za-z0-9_-]{12}")
        .hasSizeLessThanOrEqualTo(AnalysisConstants.MAX_CURSOR_CHARS);
  }

  @Test
  void osAninhadosFicamPlanosSobOPacote() throws Exception {
    final OutlinePage esperada =
        new OutlinePage(
            List.of(
                tipoDe("p.a", "Order", "Order"), tipoDe("p.a", "Order$Builder", "Order.Builder")),
            null);

    final OutlinePage pagina = pagina(outline, "pkg:p.a", null, MEIO_MILHAR);

    assertThat(pagina).isEqualTo(esperada);
  }

  @Test
  void umCursorDeOutraListagemEhDesconhecido() {
    assertCursorDesconhecido(outline, "pkg:big", cursorDe("type:other.X"));
    assertCursorDesconhecido(outline, "pkg:big", cursorDe("pkg:kilo"));
    assertCursorDesconhecido(outline, null, cursorDe("type:big.T0000"));
    assertCursorDesconhecido(outline, null, new OutlineCursor(pacoteDaOutra));
    assertCursorDesconhecido(outline, "pkg:big", new OutlineCursor(999_999));
  }

  @Test
  void naRaizOCursorDeUmPacoteForaDoConjuntoDaFase30EhDesconhecido() {
    assertCursorDesconhecido(outline, null, cursorDe("pkg:p.empty"));
  }

  @Test
  void umPaiTipoComCursorEhCursorDesconhecido() {
    assertCursorDesconhecido(outline, "type:p.a.Order", cursorDe("type:p.a.Order$Builder"));
    assertCursorDesconhecido(outline, "type:p.a.Order", cursorDe("method:p.a.Order#total()"));
  }

  @Test
  void oCursorDaUltimaLinhaEhDesconhecido() throws Exception {
    assertCursorDesconhecido(outline, null, cursorDe("pkg:p." + Z_MATEMATICO));
    assertCursorDesconhecido(outline, "pkg:kilo", cursorDe("type:kilo.K0999"));
    assertCursorDesconhecido(outline, "pkg:long", cursorDe("type:long.C"));
    assertThat(pagina(outline, null, cursorDe("pkg:p." + Z_LARGO), MEIO_MILHAR))
        .isEqualTo(new OutlinePage(List.of(pacote("p." + Z_MATEMATICO, 1)), null));
    assertThat(pagina(outline, "pkg:kilo", cursorDe("type:kilo.K0998"), MEIO_MILHAR))
        .isEqualTo(new OutlinePage(List.of(tipoDe("kilo", "K0999", "K0999")), null));
  }

  private void assertCursorDesconhecido(long analise, String pai, OutlineCursor cursor) {
    assertThatThrownBy(() -> pagina(analise, pai, cursor, MEIO_MILHAR))
        .as("o cursor %s sob %s", cursor, pai)
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            recusa ->
                assertThat(recusa.reason())
                    .isEqualTo(InvalidStructureRequest.Reason.CURSOR_UNKNOWN));
  }

  @Test
  void umPaiTipoTemAPaginaVazia() throws Exception {
    assertThat(pagina(outline, "type:p.a.Order", null, MEIO_MILHAR)).isEqualTo(OutlinePage.EMPTY);
    assertThat(pagina(outline, "type:p.a.Order$Builder", null, MEIO_MILHAR))
        .isEqualTo(OutlinePage.EMPTY);
  }

  @Test
  void umPacoteSemTipoTemAPaginaVazia() throws Exception {
    assertThat(pagina(outline, "pkg:p.empty", null, MEIO_MILHAR)).isEqualTo(OutlinePage.EMPTY);
  }

  @Test
  void umPaiAusenteOuExternoEhElementoNaoEncontrado() {
    for (final String pai :
        List.of("pkg:nope", "type:p.a.Nope", "type:java.util.List", "pkg:q", "type:q.Q")) {
      assertThatThrownBy(() -> pagina(outline, pai, null, MEIO_MILHAR))
          .as(pai)
          .isInstanceOf(ElementNotFoundException.class);
    }
    assertThatThrownBy(() -> pagina(outra, "pkg:big", null, MEIO_MILHAR))
        .isInstanceOf(ElementNotFoundException.class);
  }

  @Test
  void oChildCountEhOTotalsDaFatiaDeTipos() throws Exception {
    final List<OutlineItem> raiz = pagina(outline, null, null, MEIO_MILHAR).items();

    for (final OutlineItem item : raiz) {
      final Slice slice =
          cache.read(
              c -> GraphSliceQuery.slice(c, outline, GraphLevel.TYPE, item.id(), TYPE_BUDGET));
      assertThat(slice.totals().nodes()).as(item.id().value()).isEqualTo(item.childCount());
    }
    assertThat(raiz)
        .filteredOn(item -> item.label().equals("big"))
        .singleElement()
        .extracting(OutlineItem::childCount)
        .isEqualTo(1_201);
  }
}
