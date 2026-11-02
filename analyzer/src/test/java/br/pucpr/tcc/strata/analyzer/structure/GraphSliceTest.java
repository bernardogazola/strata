package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.ANNOTATION_REFERENCE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.EXTENDS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPLEMENTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.RETURN_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.APP_PACKAGES;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.CODE_POINT_ORDER;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.EDGE_TYPES;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.PACKAGE_BUDGET;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.TYPE_BUDGET;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.appLoc;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.appMix;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.appPagerank;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.mix;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.occurrences;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class GraphSliceTest {

  private static final String Z_LARGO = Character.toString(0xFF3A);

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final double C = 1.0 / 2048;

  private static final double ACIMA_DE_UM_301_AVOS = 0.003322259136212625;

  private static final String ORACULO_ES = "/structure/elasticsearch-with-tests.groups.txt";

  private static final String BILLING = "pkg:com.acme.billing";
  private static final String API = "pkg:com.acme.billing.api";
  private static final String ORDERS = "pkg:com.acme.orders";
  private static final String INVOICE = "type:com.acme.billing.Invoice";
  private static final String LINHA = "type:com.acme.billing.Invoice$Line";
  private static final String LEDGER = "type:com.acme.billing.Ledger";

  @TempDir private Path temp;

  private StructureCaches cache;

  @BeforeEach
  void abrir() throws Exception {
    cache = StructureCaches.create(temp);
  }

  @AfterEach
  void fechar() throws SQLException {
    cache.close();
  }

  private Slice fatia(long analise, GraphLevel nivel, String pai, SliceBudget orcamento)
      throws SQLException {
    final CodeElementId parent = pai == null ? null : new CodeElementId(pai);
    return cache.read(c -> GraphSliceQuery.slice(c, analise, nivel, parent, orcamento));
  }

  private static List<String> ids(Slice slice) {
    return slice.nodes().stream().map(SliceNode::id).toList();
  }

  private static SliceNode pkg(
      String nome,
      String rotulo,
      long loc,
      int tipos,
      int in,
      int out,
      double pr,
      ResolutionMix m) {
    return new SliceNode("pkg:" + nome, GraphNodeKind.PACKAGE, rotulo, loc, tipos, in, out, pr, m);
  }

  private static SliceNode pfx(
      String nome,
      String rotulo,
      long loc,
      int tipos,
      int in,
      int out,
      double pr,
      ResolutionMix m) {
    return new SliceNode(
        "pfx:" + nome, GraphNodeKind.PACKAGE_PREFIX, rotulo, loc, tipos, in, out, pr, m);
  }

  private static SliceNode tipoNo(
      String id, String rotulo, long loc, int in, int out, double pr, ResolutionMix m) {
    return new SliceNode(id, GraphNodeKind.TYPE, rotulo, loc, 1, in, out, pr, m);
  }

  private static SliceEdge aresta(
      String id, String de, String para, long peso, ResolutionQuality q, Object... tipos) {
    return new SliceEdge(id, de, para, types(tipos), peso, q);
  }

  private long mundoAcme(String analysisId) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long padrao = a.addPackage("", new NodeValues(5, 0, 1, 0.125, mix(1, 0, 0)));
    final long billing =
        a.addPackage("com.acme.billing", new NodeValues(40, 2, 0, 0.5, mix(8, 1, 1)));
    final long api =
        a.addPackage("com.acme.billing.api", new NodeValues(7, 0, 1, 0.125, mix(1, 0, 0)));
    final long orders =
        a.addPackage("com.acme.orders", new NodeValues(25, 1, 1, 0.25, mix(0, 2, 0)));
    a.addBarePackage("com.acme.empty");
    final long invoice = a.addType(billing, "Invoice", new NodeValues(20, 3, 1, 0.2, mix(4, 1, 0)));
    final long linha =
        a.addType(billing, "Invoice$Line", new NodeValues(6, 1, 1, 0.1, mix(1, 0, 0)));
    final long ledger = a.addType(billing, "Ledger", new NodeValues(12, 1, 1, 0.15, mix(3, 0, 1)));
    final long billingApi =
        a.addType(api, "BillingApi", new NodeValues(5, 0, 1, 0.05, mix(1, 0, 0)));
    final long order = a.addType(orders, "Order", new NodeValues(15, 1, 1, 0.2, mix(0, 2, 0)));
    a.addType(orders, "Order$Builder", new NodeValues(8, 0, 0, 0.1, ResolutionMix.NONE));
    final long main = a.addType(padrao, "Main", new NodeValues(4, 0, 1, 0.2, mix(1, 0, 0)));
    final long list = a.addExternalType("java.util.List");
    a.addMethod(order, "total");
    a.addDependency(linha, invoice, FIELD_TYPE, RESOLVED, 1);
    a.addDependency(invoice, linha, FIELD_TYPE, RESOLVED, 2);
    a.addDependency(invoice, linha, CALLS, INFERRED, 1);
    a.addDependency(ledger, invoice, PARAMETER_TYPE, RESOLVED, 3);
    a.addDependency(invoice, invoice, CALLS, RESOLVED, 2);
    a.addDependency(ledger, list, FIELD_TYPE, UNRESOLVED_EXTERNAL, 1);
    a.addDependency(billingApi, invoice, RETURN_TYPE, RESOLVED, 1);
    a.addDependency(order, ledger, CALLS, INFERRED, 2);
    a.addDependency(main, order, CALLS, RESOLVED, 1);
    a.addPackageEdge(api, billing, 1, RESOLVED, occurrences(RETURN_TYPE, 1));
    a.addPackageEdge(orders, billing, 1, INFERRED, occurrences(CALLS, 2));
    a.addPackageEdge(padrao, orders, 1, RESOLVED, occurrences(CALLS, 1));
    return a.write().pk();
  }

  @Test
  void oNivelDePacotesEhALinhaGravadaDeCadaPacoteEDeCadaAresta() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(
                pkg("", "", 5, 1, 0, 1, 0.125, mix(1, 0, 0)),
                pkg("com.acme.billing", "billing", 40, 3, 2, 0, 0.5, mix(8, 1, 1)),
                pkg("com.acme.billing.api", "billing.api", 7, 1, 0, 1, 0.125, mix(1, 0, 0)),
                pkg("com.acme.orders", "orders", 25, 2, 1, 1, 0.25, mix(0, 2, 0))),
            List.of(
                aresta("e_6c4447b6343dad1f", "pkg:", ORDERS, 1, RESOLVED, CALLS, 1),
                aresta("e_0aca0f4e960852f9", API, BILLING, 1, RESOLVED, RETURN_TYPE, 1),
                aresta("e_7c46931ef4024df5", ORDERS, BILLING, 1, INFERRED, CALLS, 2)),
            new SliceTotals(4, 3),
            false,
            false,
            PACKAGE_BUDGET);
    final long analise = mundoAcme("an_acme");

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void oNivelDeTiposEhOAgregadoDasLinhasInternasDoPacote() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.TYPE,
            new CodeElementId(BILLING),
            List.of(
                tipoNo(INVOICE, "Invoice", 20, 3, 1, 0.2, mix(4, 1, 0)),
                tipoNo(LINHA, "Invoice.Line", 6, 1, 1, 0.1, mix(1, 0, 0)),
                tipoNo(LEDGER, "Ledger", 12, 1, 1, 0.15, mix(3, 0, 1))),
            List.of(
                aresta("e_8a2c3544f30d7d97", INVOICE, LINHA, 8, INFERRED, CALLS, 1, FIELD_TYPE, 2),
                aresta("e_1cf15ec95ab0ff61", LINHA, INVOICE, 3, RESOLVED, FIELD_TYPE, 1),
                aresta("e_e76bff0c561491fb", LEDGER, INVOICE, 3, RESOLVED, PARAMETER_TYPE, 3)),
            new SliceTotals(3, 3),
            false,
            false,
            TYPE_BUDGET);
    final long analise = mundoAcme("an_acme");

    final Slice slice = fatia(analise, GraphLevel.TYPE, BILLING, TYPE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void oPacotePadraoEhPaiValidoNoNivelDeTipos() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.TYPE,
            new CodeElementId("pkg:"),
            List.of(tipoNo("type:Main", "Main", 4, 0, 1, 0.2, mix(1, 0, 0))),
            List.of(),
            new SliceTotals(1, 0),
            false,
            false,
            TYPE_BUDGET);
    final long analise = mundoAcme("an_acme");

    final Slice slice = fatia(analise, GraphLevel.TYPE, "pkg:", TYPE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void umPacoteSemTipoDaAFatiaVaziaENaoElementoNaoEncontrado() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.TYPE,
            new CodeElementId("pkg:com.acme.empty"),
            List.of(),
            List.of(),
            new SliceTotals(0, 0),
            false,
            false,
            TYPE_BUDGET);
    final long analise = mundoAcme("an_acme");

    final Slice slice = fatia(analise, GraphLevel.TYPE, "pkg:com.acme.empty", TYPE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void paiAusenteExternoOuDeOutraAnaliseEhElementoNaoEncontrado() throws Exception {
    final long acme = mundoAcme("an_acme");
    final SyntheticAnalysis outra = cache.analysis("an_outra");
    outra.addType(outra.addPackage("x", NodeValues.PLAIN), "X", NodeValues.PLAIN);
    final long outraPk = outra.write().pk();

    assertThatThrownBy(() -> fatia(acme, GraphLevel.TYPE, "pkg:com.acme.nope", TYPE_BUDGET))
        .isInstanceOf(ElementNotFoundException.class);
    assertThatThrownBy(() -> fatia(acme, GraphLevel.TYPE, "pkg:java.util", TYPE_BUDGET))
        .isInstanceOf(ElementNotFoundException.class);
    assertThatThrownBy(() -> fatia(outraPk, GraphLevel.TYPE, BILLING, TYPE_BUDGET))
        .isInstanceOf(ElementNotFoundException.class);
  }

  static Stream<Arguments> formasRecusadas() {
    return Stream.of(
        Arguments.of(GraphLevel.PACKAGE, BILLING, Reason.PARENT_NOT_ALLOWED),
        Arguments.of(GraphLevel.TYPE, null, Reason.PARENT_NOT_A_PACKAGE),
        Arguments.of(GraphLevel.TYPE, INVOICE, Reason.PARENT_NOT_A_PACKAGE),
        Arguments.of(GraphLevel.TYPE, "method:com.acme.Order#total()", Reason.PARENT_NOT_A_PACKAGE),
        Arguments.of(GraphLevel.TYPE, "file:src/Main.java", Reason.PARENT_NOT_A_PACKAGE),
        Arguments.of(GraphLevel.TYPE_NEIGHBORHOOD, INVOICE, Reason.LEVEL_NOT_SERVED));
  }

  @ParameterizedTest
  @MethodSource("formasRecusadas")
  void formaErradaDePaiEhRecusadaComASuaRazao(GraphLevel nivel, String pai, Reason razao) {
    final CodeElementId parent = pai == null ? null : new CodeElementId(pai);

    assertThatThrownBy(() -> GraphSliceQuery.requireParent(nivel, parent))
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class, recusa -> assertThat(recusa.reason()).isEqualTo(razao));
  }

  @Test
  void formasCertasDePaiPassam() {
    assertThatCode(
            () -> {
              GraphSliceQuery.requireParent(GraphLevel.PACKAGE, null);
              GraphSliceQuery.requireParent(GraphLevel.TYPE, new CodeElementId("pkg:"));
              GraphSliceQuery.requireParent(GraphLevel.TYPE, new CodeElementId(BILLING));
            })
        .doesNotThrowAnyException();
  }

  @Test
  void aMesmaFatiaSaiIgualEmDuasLeiturasEEmDuasAnalises() throws Exception {
    final long primeira = mundoAcme("an_acme_1");
    final long segunda = mundoAcme("an_acme_2");

    for (final GraphLevel nivel : List.of(GraphLevel.PACKAGE, GraphLevel.TYPE)) {
      final String pai = nivel == GraphLevel.PACKAGE ? null : BILLING;
      final SliceBudget orcamento = nivel == GraphLevel.PACKAGE ? PACKAGE_BUDGET : TYPE_BUDGET;
      final Slice uma = fatia(primeira, nivel, pai, orcamento);

      assertThat(fatia(primeira, nivel, pai, orcamento)).isEqualTo(uma);
      assertThat(fatia(segunda, nivel, pai, orcamento)).isEqualTo(uma);
      assertThat(ids(uma)).isSortedAccordingTo(CODE_POINT_ORDER);
    }
  }

  @Test
  void trezentosEUmPacotesAgregamNosGruposDerivadosAMao() throws Exception {
    final List<SliceNode> nos = new ArrayList<>();
    nos.add(pfx("app.util", "util", 1_940, 150, 2, 1, 0.7408857890915491, mix(225, 150, 75)));
    final Map<String, List<Integer>> fans =
        Map.of("app.web", List.of(0, 3), "app.data", List.of(3, 1), "app.data.p000", List.of(1, 1));
    for (int i = 0; i < APP_PACKAGES.size(); i++) {
      final String nome = APP_PACKAGES.get(i);
      if (!nome.startsWith("app.util.")) {
        final List<Integer> fan = fans.getOrDefault(nome, List.of(0, 0));
        final String rotulo = nome.substring("app.".length());
        nos.add(pkg(nome, rotulo, appLoc(i), 1, fan.get(0), fan.get(1), appPagerank(i), appMix(i)));
      }
    }
    final String util = "pfx:app.util";
    final String data = "pkg:app.data";
    final String p000 = "pkg:app.data.p000";
    final String web = "pkg:app.web";
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            nos,
            List.of(
                aresta("e_6c8121b6c43d1a71", util, data, 3, INFERRED, EXTENDS, 2, CALLS, 2),
                aresta("e_bd8158b63a985ea9", data, p000, 1, INFERRED, IMPLEMENTS, 1),
                aresta("e_434ebf1191b10453", p000, util, 3, RESOLVED, CALLS, 2, RETURN_TYPE, 1),
                aresta("e_5bcce7e5a954b1a3", web, util, 2, INFERRED, IMPORTS, 1, FIELD_TYPE, 2),
                aresta("e_020a3a22ebc247b3", web, data, 2, RESOLVED, CALLS, 3)),
            new SliceTotals(301, 8),
            false,
            true,
            PACKAGE_BUDGET);
    final long analise = cache.appWorld("an_app").pk();

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
    assertThat(slice.nodes()).hasSize(153);
  }

  @Test
  void umGrupoComTodoPacoteTemOPagerankCortadoEmUm() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(pfx("a", "a", 301, 301, 0, 0, 1.0, ResolutionMix.NONE)),
            List.of(),
            new SliceTotals(301, 0),
            false,
            true,
            PACKAGE_BUDGET);
    final SyntheticAnalysis a = cache.analysis("an_teto");
    final NodeValues valores = new NodeValues(1, 0, 0, ACIMA_DE_UM_301_AVOS, ResolutionMix.NONE);
    for (int i = 0; i <= 300; i++) {
      a.addType(a.addPackage("a.p%03d".formatted(i), valores), "T", valores);
    }
    final long analise = a.write().pk();

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
    assertThat(new BigDecimal(ACIMA_DE_UM_301_AVOS).multiply(BigDecimal.valueOf(301)))
        .as("a soma exata passa de 1, então é o teto que dá 1.0")
        .isGreaterThan(BigDecimal.ONE);
  }

  private record ArestaGerada(
      int origem,
      int destino,
      int pares,
      Map<DependencyType, Integer> tipos,
      ResolutionQuality q) {}

  private record ArestaVista(
      String source,
      String target,
      Map<DependencyType, Long> types,
      long weight,
      ResolutionQuality resolution) {

    static ArestaVista de(SliceEdge e) {
      return new ArestaVista(e.source(), e.target(), e.types(), e.weight(), e.resolution());
    }
  }

  private static LinkedHashMap<String, String> oraculoElasticsearch() throws IOException {
    try (InputStream recurso = GraphSliceTest.class.getResourceAsStream(ORACULO_ES)) {
      assertThat(recurso).as("o recurso %s", ORACULO_ES).isNotNull();
      final String texto = new String(recurso.readAllBytes(), StandardCharsets.UTF_8);
      final LinkedHashMap<String, String> grupo = new LinkedHashMap<>();
      for (final String linha : texto.split("\n")) {
        final String[] campos = linha.split("\t", -1);
        assertThat(campos).as(linha).hasSize(2);
        assertThat(campos[0]).as(linha).startsWith("pkg:");
        grupo.put(campos[0].substring("pkg:".length()), campos[1]);
      }
      assertThat(grupo).hasSize(2_129);
      assertThat(grupo.keySet()).first().isEqualTo("");
      return grupo;
    }
  }

  private static List<ArestaGerada> arestasDensas(int n) {
    final List<ArestaGerada> arestas = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      for (int k = 1; k <= 3; k++) {
        final int j = (7 * i + 331 * k) % n;
        if (j != i) {
          final int pares = 1 + (i + 3 * k) % 5;
          final Map<DependencyType, Integer> tipos = new EnumMap<>(DependencyType.class);
          tipos.put(EDGE_TYPES.get((i + k) % 8), pares);
          if (i % 2 == 1) {
            tipos.put(EDGE_TYPES.get((i + k + 3) % 8), 1);
          }
          arestas.add(new ArestaGerada(i, j, pares, tipos, (i + k) % 4 == 0 ? INFERRED : RESOLVED));
        }
      }
    }
    return arestas;
  }

  private static List<ArestaGerada> arestasEsparsas(int n) {
    return IntStream.range(0, n - 1)
        .mapToObj(i -> new ArestaGerada(i, i + 1, 1, occurrences(CALLS, 1), RESOLVED))
        .toList();
  }

  private long semearElasticsearch(String id, List<String> nomes, List<ArestaGerada> arestas)
      throws SQLException {
    final int n = nomes.size();
    final int[] fanIn = new int[n];
    final int[] fanOut = new int[n];
    for (final ArestaGerada aresta : arestas) {
      fanOut[aresta.origem()]++;
      fanIn[aresta.destino()]++;
    }
    final SyntheticAnalysis a = cache.analysis(id);
    final long[] pks = new long[n];
    for (int i = 0; i < n; i++) {
      final ResolutionMix m = mix(i % 5, i % 2, i % 3);
      pks[i] =
          a.addPackage(nomes.get(i), new NodeValues(5 + i % 11, fanIn[i], fanOut[i], 1.0 / n, m));
    }
    for (final long pacote : pks) {
      a.addType(pacote, "T", NodeValues.PLAIN);
    }
    for (final ArestaGerada e : arestas) {
      a.addPackageEdge(pks[e.origem()], pks[e.destino()], e.pares(), e.q(), e.tipos());
    }
    return a.write().pk();
  }

  private static List<SliceNode> nosEsperados(
      List<String> nomes, Map<String, String> grupo, List<ArestaGerada> arestas) {
    final int n = nomes.size();
    final Map<String, Set<Integer>> membros = new TreeMap<>(CODE_POINT_ORDER);
    for (int i = 0; i < n; i++) {
      membros.computeIfAbsent(grupo.get(nomes.get(i)), id -> new HashSet<>()).add(i);
    }
    final List<SliceNode> nos = new ArrayList<>();
    membros.forEach(
        (id, dentro) -> {
          final Set<Integer> chegam = new HashSet<>();
          final Set<Integer> saem = new HashSet<>();
          for (final ArestaGerada e : arestas) {
            if (dentro.contains(e.destino()) && !dentro.contains(e.origem())) {
              chegam.add(e.origem());
            }
            if (dentro.contains(e.origem()) && !dentro.contains(e.destino())) {
              saem.add(e.destino());
            }
          }
          long loc = 0;
          long r = 0;
          long inf = 0;
          long u = 0;
          BigDecimal pagerank = BigDecimal.ZERO;
          for (final int i : dentro) {
            loc += 5 + i % 11;
            r += i % 5;
            inf += i % 2;
            u += i % 3;
            pagerank = pagerank.add(new BigDecimal(1.0 / n));
          }
          final GraphNodeKind kind =
              id.startsWith("pfx:") ? GraphNodeKind.PACKAGE_PREFIX : GraphNodeKind.PACKAGE;
          final double pr = Math.min(1.0, pagerank.doubleValue());
          nos.add(
              new SliceNode(
                  id,
                  kind,
                  id.substring(4),
                  loc,
                  dentro.size(),
                  chegam.size(),
                  saem.size(),
                  pr,
                  mix(r, inf, u)));
        });
    return nos;
  }

  private static List<ArestaVista> arestasEsperadas(
      List<String> nomes, Map<String, String> grupo, List<ArestaGerada> arestas, int total) {
    final Map<List<String>, ArestaVista> porPar = new HashMap<>();
    for (final ArestaGerada e : arestas) {
      final String de = grupo.get(nomes.get(e.origem()));
      final String para = grupo.get(nomes.get(e.destino()));
      if (!de.equals(para)) {
        final ArestaVista antes =
            porPar.getOrDefault(
                List.of(de, para), new ArestaVista(de, para, Map.of(), 0, RESOLVED));
        final Map<DependencyType, Long> somados = new EnumMap<>(DependencyType.class);
        somados.putAll(antes.types());
        e.tipos().forEach((tipo, quantas) -> somados.merge(tipo, (long) quantas, Long::sum));
        final boolean inferida = antes.resolution() == INFERRED || e.q() == INFERRED;
        final long peso = antes.weight() + e.pares();
        porPar.put(
            List.of(de, para),
            new ArestaVista(de, para, somados, peso, inferida ? INFERRED : RESOLVED));
      }
    }
    assertThat(porPar).as("as arestas de grupo do oráculo (es_world.py)").hasSize(total);
    final Comparator<ArestaVista> fio =
        Comparator.comparing(ArestaVista::source, CODE_POINT_ORDER)
            .thenComparing(ArestaVista::target, CODE_POINT_ORDER);
    final int teto = AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES;
    return porPar.values().stream()
        .sorted(Comparator.comparingLong(ArestaVista::weight).reversed().thenComparing(fio))
        .limit(teto)
        .sorted(fio)
        .toList();
  }

  @Test
  void nomesReaisDoElasticsearchComArestasDensasAgregamETruncam() throws Exception {
    final LinkedHashMap<String, String> grupo = oraculoElasticsearch();
    final List<String> nomes = List.copyOf(grupo.keySet());
    final List<ArestaGerada> arestas = arestasDensas(nomes.size());
    final List<SliceNode> nos = nosEsperados(nomes, grupo, arestas);
    final List<ArestaVista> mantidas = arestasEsperadas(nomes, grupo, arestas, 3_164);
    final String esql = "pfx:org.elasticsearch.xpack.esql";
    final ArestaVista maisPesada =
        new ArestaVista(
            "pfx:org.elasticsearch.xpack.security",
            esql,
            types(
                ANNOTATION_REFERENCE,
                19,
                IMPORTS,
                20,
                CALLS,
                22,
                EXTENDS,
                20,
                IMPLEMENTS,
                22,
                PARAMETER_TYPE,
                19,
                FIELD_TYPE,
                17,
                RETURN_TYPE,
                18),
            135,
            INFERRED);
    final ArestaVista primeira =
        new ArestaVista(
            "pfx:fixture",
            "pfx:org.elasticsearch.entitlement",
            types(IMPLEMENTS, 5, PARAMETER_TYPE, 1, CALLS, 1),
            6,
            RESOLVED);
    final SliceNode grupoEsql =
        pfx(
            esql.substring(4),
            esql.substring(4),
            1_523,
            152,
            390,
            390,
            0.07139502113668389,
            mix(305, 76, 153));
    final long analise = semearElasticsearch("an_es_densa", nomes, arestas);

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice.aggregated()).isTrue();
    assertThat(slice.truncated()).isTrue();
    assertThat(slice.totals()).isEqualTo(new SliceTotals(2_129, 6_384));
    assertThat(slice.budget()).isEqualTo(PACKAGE_BUDGET);
    assertThat(slice.nodes()).hasSize(300).isEqualTo(nos).contains(grupoEsql);
    final List<ArestaVista> vistas = slice.edges().stream().map(ArestaVista::de).toList();
    assertThat(vistas).isEqualTo(mantidas).contains(maisPesada).startsWith(primeira);
    assertThat(vistas).hasSize(1_200).filteredOn(e -> e.weight() == 5).hasSize(381);
    assertThat(vistas.stream().mapToLong(ArestaVista::weight).summaryStatistics())
        .satisfies(
            s -> {
              assertThat(s.getSum()).isEqualTo(13_398);
              assertThat(s.getMin()).isEqualTo(5);
            });
    assertThat(vistas.stream().flatMap(e -> e.types().values().stream()).mapToLong(x -> x).sum())
        .isEqualTo(15_493);
    assertThat(vistas).filteredOn(e -> e.resolution() == INFERRED).hasSize(737);
    assertThat(slice.edges().stream().map(SliceEdge::id).distinct()).hasSize(1_200);
  }

  @Test
  void nomesReaisDoElasticsearchComArestasEsparsasSoAgregam() throws Exception {
    final LinkedHashMap<String, String> grupo = oraculoElasticsearch();
    final List<String> nomes = List.copyOf(grupo.keySet());
    final List<ArestaGerada> arestas = arestasEsparsas(nomes.size());
    final List<SliceNode> nos = nosEsperados(nomes, grupo, arestas);
    final List<ArestaVista> mantidas = arestasEsperadas(nomes, grupo, arestas, 299);
    final SliceNode grupoEsql =
        pfx(
            "org.elasticsearch.xpack.esql",
            "org.elasticsearch.xpack.esql",
            1_523,
            152,
            1,
            1,
            0.07139502113668389,
            mix(305, 76, 153));
    final long analise = semearElasticsearch("an_es_esparsa", nomes, arestas);

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice.aggregated()).isTrue();
    assertThat(slice.truncated()).isFalse();
    assertThat(slice.totals()).isEqualTo(new SliceTotals(2_129, 2_128));
    assertThat(slice.nodes()).hasSize(300).isEqualTo(nos).contains(grupoEsql);
    assertThat(slice.edges().stream().map(ArestaVista::de).toList()).isEqualTo(mantidas);
    assertThat(slice.edges().stream().mapToLong(SliceEdge::weight).sum()).isEqualTo(299);
  }

  @Test
  void milETrezentasArestasSemAgregarMantemAsMilEDuzentasMaisPesadas() throws Exception {
    final Set<ArestaVista> esperadas = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      for (int o = 1; o <= 26; o++) {
        final long peso = o <= 22 ? 2 : 1;
        if (o <= 22 || i <= 24) {
          final String de = "pkg:p%02d".formatted(i);
          final String para = "pkg:p%02d".formatted((i + o) % 50);
          esperadas.add(new ArestaVista(de, para, types(CALLS, peso), peso, RESOLVED));
        }
      }
    }
    final SyntheticAnalysis a = cache.analysis("an_cinquenta");
    final long[] pks = new long[50];
    for (int i = 49; i >= 0; i--) {
      pks[i] = a.addPackage("p%02d".formatted(i), new NodeValues(1, 26, 26, 0.02, mix(0, 0, 0)));
    }
    for (final long pacote : pks) {
      a.addType(pacote, "T", NodeValues.PLAIN);
    }
    for (int o = 1; o <= 26; o++) {
      for (int i = 0; i < 50; i++) {
        final int peso = o <= 22 ? 2 : 1;
        a.addPackageEdge(pks[i], pks[(i + o) % 50], peso, RESOLVED, occurrences(CALLS, peso));
      }
    }
    final long analise = a.write().pk();

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice.aggregated()).isFalse();
    assertThat(slice.truncated()).isTrue();
    assertThat(slice.totals()).isEqualTo(new SliceTotals(50, 1_300));
    assertThat(ids(slice))
        .isEqualTo(IntStream.range(0, 50).mapToObj("pkg:p%02d"::formatted).toList());
    assertThat(slice.edges().stream().map(ArestaVista::de).toList())
        .hasSize(1_200)
        .containsExactlyInAnyOrderElementsOf(esperadas)
        .isSortedAccordingTo(
            Comparator.comparing(ArestaVista::source, CODE_POINT_ORDER)
                .thenComparing(ArestaVista::target, CODE_POINT_ORDER));
  }

  private long mundoTri(String analysisId, double ax, double ay, double b) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long pax = a.addPackage("a.x", new NodeValues(3, 0, 1, ax, mix(1, 0, 0)));
    final long pay = a.addPackage("a.y", new NodeValues(4, 1, 0, ay, mix(0, 1, 0)));
    final long pb = a.addPackage("b", new NodeValues(5, 1, 1, b, mix(0, 0, 1)));
    for (final long pacote : List.of(pax, pay, pb)) {
      a.addType(pacote, "T", NodeValues.PLAIN);
    }
    a.addPackageEdge(pax, pb, 1, RESOLVED, occurrences(CALLS, 1));
    a.addPackageEdge(pb, pay, 1, RESOLVED, occurrences(CALLS, 1));
    return a.write().pk();
  }

  @Test
  void limiteUmNumInicioDeDoisGruposMantemOMaiorPagerank() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(pkg("b", "b", 5, 1, 1, 1, 0.6, mix(0, 0, 1))),
            List.of(),
            new SliceTotals(3, 2),
            true,
            true,
            new SliceBudget(1, AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES));
    final long analise = mundoTri("an_tri", 0.2, 0.2, 0.6);

    final Slice slice =
        fatia(analise, GraphLevel.PACKAGE, null, SliceBudget.of(GraphLevel.PACKAGE, 1));

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void empateNoTransbordamentoCaiNoIdDoNo() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(pfx("a", "a", 7, 2, 1, 1, 0.5, mix(1, 1, 0))),
            List.of(),
            new SliceTotals(3, 2),
            true,
            true,
            new SliceBudget(1, AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES));
    final long analise = mundoTri("an_tri_empate", 0.25, 0.25, 0.5);

    final Slice slice =
        fatia(analise, GraphLevel.PACKAGE, null, SliceBudget.of(GraphLevel.PACKAGE, 1));

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void limiteDoisNoMesmoMundoAgregaSemTruncar() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(
                pfx("a", "a", 7, 2, 1, 1, 0.4, mix(1, 1, 0)),
                pkg("b", "b", 5, 1, 1, 1, 0.6, mix(0, 0, 1))),
            List.of(
                aresta("e_03702e1d207d75c6", "pfx:a", "pkg:b", 1, RESOLVED, CALLS, 1),
                aresta("e_a8def281c817bcb2", "pkg:b", "pfx:a", 1, RESOLVED, CALLS, 1)),
            new SliceTotals(3, 2),
            false,
            true,
            new SliceBudget(2, AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES));
    final long analise = mundoTri("an_tri", 0.2, 0.2, 0.6);

    final Slice slice =
        fatia(analise, GraphLevel.PACKAGE, null, SliceBudget.of(GraphLevel.PACKAGE, 2));

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void limiteIgualAoNumeroDePacotesNaoAgrega() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(
                pkg("a.x", "a.x", 3, 1, 0, 1, 0.2, mix(1, 0, 0)),
                pkg("a.y", "a.y", 4, 1, 1, 0, 0.2, mix(0, 1, 0)),
                pkg("b", "b", 5, 1, 1, 1, 0.6, mix(0, 0, 1))),
            List.of(
                aresta("e_ccdbd9142a7770f4", "pkg:a.x", "pkg:b", 1, RESOLVED, CALLS, 1),
                aresta("e_75c24988723945a3", "pkg:b", "pkg:a.y", 1, RESOLVED, CALLS, 1)),
            new SliceTotals(3, 2),
            false,
            false,
            new SliceBudget(3, AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES));
    final long analise = mundoTri("an_tri", 0.2, 0.2, 0.6);

    final Slice slice =
        fatia(analise, GraphLevel.PACKAGE, null, SliceBudget.of(GraphLevel.PACKAGE, 3));

    assertThat(slice).isEqualTo(esperada);
  }

  @Test
  void semLimiteOMesmoMundoNaoAgrega() throws Exception {
    final Slice esperada =
        new Slice(
            GraphLevel.PACKAGE,
            null,
            List.of(
                pkg("a.x", "a.x", 3, 1, 0, 1, 0.2, mix(1, 0, 0)),
                pkg("a.y", "a.y", 4, 1, 1, 0, 0.2, mix(0, 1, 0)),
                pkg("b", "b", 5, 1, 1, 1, 0.6, mix(0, 0, 1))),
            List.of(
                aresta("e_ccdbd9142a7770f4", "pkg:a.x", "pkg:b", 1, RESOLVED, CALLS, 1),
                aresta("e_75c24988723945a3", "pkg:b", "pkg:a.y", 1, RESOLVED, CALLS, 1)),
            new SliceTotals(3, 2),
            false,
            false,
            PACKAGE_BUDGET);
    final long analise = mundoTri("an_tri", 0.2, 0.2, 0.6);

    final Slice slice = fatia(analise, GraphLevel.PACKAGE, null, PACKAGE_BUDGET);

    assertThat(slice).isEqualTo(esperada);
  }

  private static List<String> tiposDoBig() {
    final List<String> nomes = new ArrayList<>();
    for (int k = 0; k <= 997; k++) {
      nomes.add("T%04d".formatted(k));
      if (k == 2) {
        nomes.add("T0002$Inner");
      }
    }
    nomes.add(Z_LARGO);
    nomes.add(Z_MATEMATICO);
    return List.copyOf(nomes);
  }

  private static double pagerankDoBig(String nome) {
    return switch (nome) {
      case "T0500" -> 5 * C;
      case "T0003", "T0007", "T0100" -> 4 * C;
      default -> C;
    };
  }

  private long mundoBig(String analysisId, boolean ordemInversa) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long big = a.addPackage("big", new NodeValues(1, 0, 0, 0.5, ResolutionMix.NONE));
    final long other = a.addPackage("other", new NodeValues(1, 0, 0, 0.5, ResolutionMix.NONE));
    final Map<String, NodeValues> especiais =
        Map.of(
            "T0000",
            new NodeValues(30, 1, 2, C, mix(3, 5, 0)),
            "T0001",
            new NodeValues(7, 1, 0, C, mix(5, 0, 0)),
            "T0002",
            new NodeValues(7, 1, 0, C, ResolutionMix.NONE),
            "T0002$Inner",
            new NodeValues(7, 0, 1, C, mix(1, 0, 0)),
            "T0003",
            new NodeValues(7, 0, 1, 4 * C, mix(1, 0, 0)),
            "T0004",
            new NodeValues(7, 0, 1, C, mix(2, 0, 1)),
            "T0005",
            new NodeValues(7, 1, 0, C, ResolutionMix.NONE),
            "T0006",
            new NodeValues(7, 1, 1, C, mix(5, 0, 0)),
            Z_MATEMATICO,
            new NodeValues(7, 1, 0, C, ResolutionMix.NONE));
    final List<String> nomes = new ArrayList<>(tiposDoBig());
    if (ordemInversa) {
      Collections.reverse(nomes);
    }
    final Map<String, Long> pk = new HashMap<>();
    for (final String nome : nomes) {
      final NodeValues comum = new NodeValues(7, 0, 0, pagerankDoBig(nome), ResolutionMix.NONE);
      pk.put(nome, a.addType(big, nome, especiais.getOrDefault(nome, comum)));
    }
    final long x = a.addType(other, "X", new NodeValues(1, 1, 1, C, mix(1, 0, 0)));
    final long list = a.addExternalType("java.util.List");
    a.addDependency(pk.get("T0000"), pk.get("T0001"), CALLS, RESOLVED, 3);
    a.addDependency(pk.get("T0000"), pk.get("T0001"), FIELD_TYPE, INFERRED, 1);
    a.addDependency(pk.get("T0001"), pk.get("T0001"), CALLS, RESOLVED, 5);
    a.addDependency(pk.get("T0002$Inner"), pk.get("T0002"), EXTENDS, RESOLVED, 1);
    a.addDependency(pk.get("T0003"), pk.get(Z_MATEMATICO), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.get("T0004"), x, CALLS, RESOLVED, 2);
    a.addDependency(pk.get("T0004"), list, FIELD_TYPE, UNRESOLVED_EXTERNAL, 1);
    a.addDependency(x, pk.get("T0005"), CALLS, RESOLVED, 1);
    a.addDependency(pk.get("T0006"), pk.get("T0000"), IMPLEMENTS, RESOLVED, 2);
    a.addDependency(pk.get("T0006"), pk.get("T0000"), PARAMETER_TYPE, RESOLVED, 1);
    a.addDependency(pk.get("T0006"), pk.get("T0000"), RETURN_TYPE, RESOLVED, 2);
    a.addDependency(pk.get("T0000"), pk.get("T0006"), ANNOTATION_REFERENCE, INFERRED, 4);
    return a.write().pk();
  }

  private static String big(String nome) {
    return "type:big." + nome;
  }

  @Test
  void milEUmTiposCortamOMenorPagerankEDesempatamPorIdEmPontoDeCodigo() throws Exception {
    final List<String> mantidos =
        tiposDoBig().stream()
            .filter(n -> !n.equals(Z_MATEMATICO))
            .map(GraphSliceTest::big)
            .toList();
    final List<SliceNode> amostra =
        List.of(
            tipoNo(big("T0000"), "T0000", 30, 1, 2, C, mix(3, 5, 0)),
            tipoNo(big("T0002$Inner"), "T0002.Inner", 7, 0, 1, C, mix(1, 0, 0)),
            tipoNo(big("T0005"), "T0005", 7, 1, 0, C, ResolutionMix.NONE),
            tipoNo(big(Z_LARGO), Z_LARGO, 7, 0, 0, C, ResolutionMix.NONE));
    final List<SliceEdge> arestas =
        List.of(
            aresta(
                "e_e84472c9f5b48d8c",
                big("T0000"),
                big("T0001"),
                9,
                INFERRED,
                CALLS,
                3,
                FIELD_TYPE,
                1),
            aresta(
                "e_e84471c9f5b48bd9",
                big("T0000"),
                big("T0006"),
                4,
                INFERRED,
                ANNOTATION_REFERENCE,
                4),
            aresta("e_0d579db864f59a0b", big("T0002$Inner"), big("T0002"), 4, RESOLVED, EXTENDS, 1),
            aresta(
                "e_59225c6e845bd125",
                big("T0006"),
                big("T0000"),
                13,
                RESOLVED,
                IMPLEMENTS,
                2,
                PARAMETER_TYPE,
                1,
                RETURN_TYPE,
                2));
    final long analise = mundoBig("an_big", false);

    final Slice slice = fatia(analise, GraphLevel.TYPE, "pkg:big", TYPE_BUDGET);

    assertThat(ids(slice)).hasSize(1_000).isEqualTo(mantidos).doesNotContain(big(Z_MATEMATICO));
    assertThat(slice.nodes()).containsAll(amostra);
    assertThat(slice.edges()).isEqualTo(arestas);
    assertThat(slice.level()).isEqualTo(GraphLevel.TYPE);
    assertThat(slice.parentId()).isEqualTo(new CodeElementId("pkg:big"));
    assertThat(slice.totals()).isEqualTo(new SliceTotals(1_001, 5));
    assertThat(slice.truncated()).isTrue();
    assertThat(slice.aggregated()).isFalse();
    assertThat(slice.budget()).isEqualTo(TYPE_BUDGET);
  }

  @Test
  void limiteCincoMilNoNivelDeTiposEhCortadoParaMil() throws Exception {
    final long analise = mundoBig("an_big", false);

    final SliceBudget orcamento = SliceBudget.of(GraphLevel.TYPE, 5_000);
    final Slice slice = fatia(analise, GraphLevel.TYPE, "pkg:big", orcamento);

    assertThat(orcamento).isEqualTo(new SliceBudget(1_000, 5_000));
    assertThat(slice).isEqualTo(fatia(analise, GraphLevel.TYPE, "pkg:big", TYPE_BUDGET));
  }

  @Test
  void limiteTresMantemOsMaioresPagerankEDesempataPorId() throws Exception {
    final SliceBudget tres = new SliceBudget(3, AnalysisConstants.GRAPH_SLICE_TYPE_MAX_EDGES);
    final long analise = mundoBig("an_big", false);

    final Slice slice =
        fatia(analise, GraphLevel.TYPE, "pkg:big", SliceBudget.of(GraphLevel.TYPE, 3));

    assertThat(ids(slice)).containsExactly(big("T0003"), big("T0007"), big("T0500"));
    assertThat(slice.edges()).isEmpty();
    assertThat(slice.totals()).isEqualTo(new SliceTotals(1_001, 5));
    assertThat(slice.truncated()).isTrue();
    assertThat(slice.budget()).isEqualTo(tres);
  }

  @Test
  void chavesInternasEmOrdemInversaDaoAsMesmasFatias() throws Exception {
    final long direta = mundoBig("an_big", false);
    final long inversa = mundoBig("an_big_inversa", true);

    for (final SliceBudget orcamento :
        List.of(TYPE_BUDGET, new SliceBudget(3, AnalysisConstants.GRAPH_SLICE_TYPE_MAX_EDGES))) {
      assertThat(fatia(inversa, GraphLevel.TYPE, "pkg:big", orcamento))
          .isEqualTo(fatia(direta, GraphLevel.TYPE, "pkg:big", orcamento));
    }
  }
}
