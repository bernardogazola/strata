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
import static br.pucpr.tcc.strata.analyzer.structure.DependencyDirection.IN;
import static br.pucpr.tcc.strata.analyzer.structure.DependencyDirection.OUT;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.TYPE_BUDGET;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.occurrences;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.types;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Declaration;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.GraphValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Metrics;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DependencyPageTest {

  private static final String Z_LARGO = Character.toString(0xFF3A);

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final String LONGO = "ext." + Z_MATEMATICO.repeat(600);

  private static final String ALVO = "type:app.core.Alvo";
  private static final String INTERNO = "type:app.core.Alvo$Interno";
  private static final String BETA = "type:app.core.Beta";
  private static final String DELTA = "type:app.core.Delta";
  private static final String GAMA = "type:app.core.Gama";
  private static final String PORTA = "type:app.api.Porta";
  private static final String AJUDA = "type:app.util.Ajuda";
  private static final String SOZINHO = "type:app.util.Sozinho";
  private static final String UTIL = "type:app.util.Util";
  private static final String EPSILON = "type:app.web.Epsilon";
  private static final String ETA = "type:app.web.Eta";
  private static final String ZETA_LARGO = "type:app.web." + Z_LARGO + "eta";
  private static final String ZETA_MATEMATICO = "type:app.web." + Z_MATEMATICO + "eta";
  private static final String STRING = "type:java.lang.String";
  private static final String LIST = "type:java.util.List";
  private static final String FUTURE = "type:java.util.concurrent.Future";
  private static final String FOO = "type:Foo";
  private static final String LIB = "type:org.ext.Lib";
  private static final String EXT_LONGO = "type:" + LONGO;
  private static final String CORE = "pkg:app.core";
  private static final String API = "pkg:app.api";
  private static final String UTIL_PKG = "pkg:app.util";
  private static final String WEB = "pkg:app.web";

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

  private record Mundo(long pk, Map<String, Long> pks) {

    long de(String id) {
      final Long elementPk = pks.get(id);
      if (elementPk == null) {
        throw new IllegalArgumentException("não está no mundo: " + id);
      }
      return elementPk;
    }
  }

  private DependencyPage pagina(
      long analise, String id, DependencyDirection sentido, DependencyCursor apos, int limite)
      throws SQLException {
    return cache.read(
        c -> DependencyPageQuery.page(c, analise, new CodeElementId(id), sentido, apos, limite));
  }

  private List<DependencyEntry> paginaAteOFim(
      Mundo mundo, String id, DependencyDirection sentido, int limite) throws SQLException {
    final List<DependencyEntry> todas = new ArrayList<>();
    final List<DependencyTotals> totais = new ArrayList<>();
    DependencyCursor cursor = null;
    for (int volta = 0; volta < 10_000; volta++) {
      final DependencyPage page = pagina(mundo.pk(), id, sentido, cursor, limite);
      todas.addAll(page.items());
      totais.add(page.totals());
      if (page.next() == null) {
        assertThat(page.items().size()).as("a última página").isBetween(0, limite);
        assertThat(totais).as("os totais de toda página").containsOnly(totais.getFirst());
        return todas;
      }
      assertThat(page.items()).as("uma página com cursor está cheia").hasSize(limite);
      final DependencyEntry ultima = page.items().getLast();
      assertThat(page.next())
          .as("o cursor da página %d", volta)
          .isEqualTo(
              new DependencyCursor(sentido, mundo.de(id), mundo.de(ultima.elementId().value())));
      cursor = page.next();
    }
    throw new AssertionError("mais de 10 000 páginas: um cursor que nunca avança");
  }

  private static DependencyEntry linha(
      String edgeId,
      String id,
      String rotulo,
      String nome,
      String pacote,
      boolean ambigua,
      ResolutionQuality qualidade,
      long peso,
      Object... tipos) {
    final CodeElementId elemento = new CodeElementId(id);
    final boolean externa = pacote == null && elemento.kind() == ElementKind.TYPE;
    return new DependencyEntry(
        edgeId,
        elemento,
        elemento.kind(),
        rotulo,
        nome,
        pacote == null ? null : new CodeElementId(pacote),
        externa,
        ambigua,
        qualidade,
        types(tipos),
        peso);
  }

  private static void assertRefused(ThrowingCallable chamada, Reason razao) {
    assertThatThrownBy(chamada)
        .isInstanceOfSatisfying(
            InvalidStructureRequest.class,
            recusa -> {
              assertThat(recusa.reason()).isEqualTo(razao);
              assertThat(recusa.detail()).isEqualTo(razao.detail());
            });
  }

  private static Metrics metricas() {
    return new Metrics(10, 1, 1, 1, 1);
  }

  private static Metrics metricasDePacote() {
    return new Metrics(10, 1, 1, 1, null);
  }

  private static GraphValues grafo(int in, int out, int outExterno) {
    return new GraphValues(in, out, outExterno, 0.05, null, ResolutionMix.NONE);
  }

  private static Declaration classe() {
    return new Declaration(TypeKind.CLASS, "", false, null, 1, 10);
  }

  private Mundo mundoDep(String analysisId) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final Map<String, Long> pks = new HashMap<>();
    final long web = a.addPackage("app.web", false, metricasDePacote(), grafo(1, 1, 0));
    final long util = a.addPackage("app.util", false, metricasDePacote(), grafo(1, 1, 0));
    final long core = a.addPackage("app.core", false, metricasDePacote(), grafo(3, 3, 0));
    final long api = a.addPackage("app.api", false, metricasDePacote(), grafo(1, 1, 0));
    pks.put(WEB, web);
    pks.put(UTIL_PKG, util);
    pks.put(CORE, core);
    pks.put(API, api);
    pks.put(ZETA_LARGO, a.addType(web, Z_LARGO + "eta", classe(), metricas(), grafo(1, 1, 0)));
    pks.put(
        ZETA_MATEMATICO,
        a.addType(web, Z_MATEMATICO + "eta", classe(), metricas(), grafo(1, 1, 0)));
    pks.put(ETA, a.addType(web, "Eta", classe(), metricas(), grafo(0, 1, 0)));
    pks.put(EPSILON, a.addType(web, "Epsilon", classe(), metricas(), grafo(0, 1, 0)));
    pks.put(UTIL, a.addType(util, "Util", classe(), metricas(), grafo(1, 1, 0)));
    pks.put(SOZINHO, a.addType(util, "Sozinho", classe(), metricas(), grafo(0, 0, 0)));
    pks.put(AJUDA, a.addType(util, "Ajuda", classe(), metricas(), grafo(0, 1, 0)));
    pks.put(GAMA, a.addType(core, "Gama", classe(), metricas(), grafo(1, 2, 0)));
    pks.put(DELTA, a.addType(core, "Delta", classe(), metricas(), grafo(1, 1, 0)));
    pks.put(BETA, a.addType(core, "Beta", classe(), metricas(), grafo(3, 1, 0)));
    pks.put(INTERNO, a.addType(core, "Alvo$Interno", classe(), metricas(), grafo(1, 1, 0)));
    pks.put(ALVO, a.addType(core, "Alvo", classe(), metricas(), grafo(8, 5, 4)));
    pks.put(PORTA, a.addType(api, "Porta", classe(), metricas(), grafo(1, 2, 0)));
    pks.put(LIB, a.addExternalType("org.ext.Lib"));
    pks.put(FUTURE, a.addExternalType("java.util.concurrent.Future"));
    pks.put(LIST, a.addExternalType("java.util.List"));
    pks.put(STRING, a.addExternalType("java.lang.String"));
    pks.put(EXT_LONGO, a.addExternalType(LONGO));
    pks.put(FOO, a.addExternalType("Foo"));
    final Function<String, Long> pk = pks::get;

    a.addDependency(pk.apply(GAMA), pk.apply(ALVO), EXTENDS, RESOLVED, 1);
    a.addDependency(pk.apply(GAMA), pk.apply(ALVO), CALLS, INFERRED, 3);
    a.addDependency(pk.apply(GAMA), pk.apply(ALVO), ANNOTATION_REFERENCE, RESOLVED, 1);
    a.addDependency(pk.apply(INTERNO), pk.apply(ALVO), FIELD_TYPE, RESOLVED, 2);
    a.addDependency(pk.apply(DELTA), pk.apply(ALVO), CALLS, RESOLVED, 2);
    a.addDependency(pk.apply(BETA), pk.apply(ALVO), FIELD_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ZETA_LARGO), pk.apply(ALVO), FIELD_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ZETA_MATEMATICO), pk.apply(ALVO), CALLS, INFERRED, 1);
    a.addDependency(pk.apply(ZETA_MATEMATICO), pk.apply(ALVO), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.apply(EPSILON), pk.apply(ALVO), PARAMETER_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ETA), pk.apply(ALVO), PARAMETER_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(ALVO), CALLS, RESOLVED, 5);
    a.addDependency(pk.apply(ALVO), pk.apply(UTIL), RETURN_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(UTIL), PARAMETER_TYPE, RESOLVED, 2);
    a.addDependency(pk.apply(ALVO), pk.apply(INTERNO), FIELD_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(BETA), CALLS, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(ZETA_LARGO), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(ZETA_MATEMATICO), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(STRING), FIELD_TYPE, RESOLVED, 2);
    a.addDependency(pk.apply(ALVO), pk.apply(LIB), IMPLEMENTS, UNRESOLVED_EXTERNAL, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(FOO), CALLS, UNRESOLVED_EXTERNAL, 1, true);
    a.addDependency(pk.apply(ALVO), pk.apply(FOO), PARAMETER_TYPE, UNRESOLVED_EXTERNAL, 1, false);
    a.addDependency(pk.apply(ALVO), pk.apply(LIST), FIELD_TYPE, RESOLVED, 1);
    a.addDependency(pk.apply(ALVO), pk.apply(FUTURE), RETURN_TYPE, RESOLVED, 1);
    a.addDependency(
        pk.apply(ALVO), pk.apply(EXT_LONGO), ANNOTATION_REFERENCE, UNRESOLVED_EXTERNAL, 1);
    a.addDependency(pk.apply(UTIL), pk.apply(BETA), CALLS, RESOLVED, 1);
    a.addDependency(pk.apply(AJUDA), pk.apply(DELTA), FIELD_TYPE, RESOLVED, 9);
    a.addDependency(pk.apply(PORTA), pk.apply(BETA), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.apply(PORTA), pk.apply(GAMA), IMPORTS, RESOLVED, 1);
    a.addDependency(pk.apply(GAMA), pk.apply(PORTA), ANNOTATION_REFERENCE, INFERRED, 3);

    a.addPackageEdge(
        web,
        core,
        4,
        INFERRED,
        occurrences(IMPORTS, 1, CALLS, 1, FIELD_TYPE, 1, PARAMETER_TYPE, 2));
    a.addPackageEdge(util, core, 2, RESOLVED, occurrences(CALLS, 1, FIELD_TYPE, 9));
    a.addPackageEdge(api, core, 2, RESOLVED, occurrences(IMPORTS, 2));
    a.addPackageEdge(core, web, 2, RESOLVED, occurrences(IMPORTS, 2));
    a.addPackageEdge(core, util, 1, RESOLVED, occurrences(PARAMETER_TYPE, 2, RETURN_TYPE, 1));
    a.addPackageEdge(core, api, 1, INFERRED, occurrences(ANNOTATION_REFERENCE, 3));
    return new Mundo(a.write().pk(), Map.copyOf(pks));
  }

  private static List<DependencyEntry> inDeAlvo() {
    return List.of(
        linha(
            "e_a5ef210f1598275b",
            GAMA,
            "Gama",
            "app.core.Gama",
            CORE,
            false,
            INFERRED,
            11,
            EXTENDS,
            1,
            CALLS,
            3,
            ANNOTATION_REFERENCE,
            1),
        linha(
            "e_0dd520e385f22334",
            INTERNO,
            "Alvo.Interno",
            "app.core.Alvo.Interno",
            CORE,
            false,
            RESOLVED,
            6,
            FIELD_TYPE,
            2),
        linha(
            "e_182cc9fb16608a5d",
            DELTA,
            "Delta",
            "app.core.Delta",
            CORE,
            false,
            RESOLVED,
            4,
            CALLS,
            2),
        linha(
            "e_11ccab20eddd0f37",
            BETA,
            "Beta",
            "app.core.Beta",
            CORE,
            false,
            RESOLVED,
            3,
            FIELD_TYPE,
            1),
        linha(
            "e_9d51fef1e81a3033",
            ZETA_LARGO,
            Z_LARGO + "eta",
            "app.web." + Z_LARGO + "eta",
            WEB,
            false,
            RESOLVED,
            3,
            FIELD_TYPE,
            1),
        linha(
            "e_2e308dddcaf009b4",
            ZETA_MATEMATICO,
            Z_MATEMATICO + "eta",
            "app.web." + Z_MATEMATICO + "eta",
            WEB,
            false,
            INFERRED,
            3,
            IMPORTS,
            1,
            CALLS,
            1),
        linha(
            "e_07e3349ee9101faa",
            EPSILON,
            "Epsilon",
            "app.web.Epsilon",
            WEB,
            false,
            RESOLVED,
            1,
            PARAMETER_TYPE,
            1),
        linha(
            "e_38afeaf06cf19250",
            ETA,
            "Eta",
            "app.web.Eta",
            WEB,
            false,
            RESOLVED,
            1,
            PARAMETER_TYPE,
            1));
  }

  private static List<DependencyEntry> outDeAlvo() {
    final String rotuloLongo = LONGO.substring(0, LONGO.offsetByCodePoints(0, 512));
    return List.of(
        linha(
            "e_07d19bb2e9cb5c98",
            UTIL,
            "Util",
            "app.util.Util",
            UTIL_PKG,
            false,
            RESOLVED,
            4,
            PARAMETER_TYPE,
            2,
            RETURN_TYPE,
            1),
        linha(
            "e_5d4d319de8e8b412",
            INTERNO,
            "Alvo.Interno",
            "app.core.Alvo.Interno",
            CORE,
            false,
            RESOLVED,
            3,
            FIELD_TYPE,
            1),
        linha(
            "e_0b4dded4cca8fc17",
            BETA,
            "Beta",
            "app.core.Beta",
            CORE,
            false,
            RESOLVED,
            2,
            CALLS,
            1),
        linha(
            "e_15149d342e2c3943",
            ZETA_LARGO,
            Z_LARGO + "eta",
            "app.web." + Z_LARGO + "eta",
            WEB,
            false,
            RESOLVED,
            1,
            IMPORTS,
            1),
        linha(
            "e_17fbb3c957ab5452",
            ZETA_MATEMATICO,
            Z_MATEMATICO + "eta",
            "app.web." + Z_MATEMATICO + "eta",
            WEB,
            false,
            RESOLVED,
            1,
            IMPORTS,
            1),
        linha(
            "e_55260a845656f238",
            STRING,
            "java.lang.String",
            "java.lang.String",
            null,
            false,
            RESOLVED,
            6,
            FIELD_TYPE,
            2),
        linha(
            "e_727304a26fff275d",
            LIB,
            "org.ext.Lib",
            "org.ext.Lib",
            null,
            false,
            UNRESOLVED_EXTERNAL,
            4,
            IMPLEMENTS,
            1),
        linha(
            "e_092cafbc0c38fad7",
            FOO,
            "Foo",
            "Foo",
            null,
            true,
            UNRESOLVED_EXTERNAL,
            3,
            CALLS,
            1,
            PARAMETER_TYPE,
            1),
        linha(
            "e_fc8d77ca63f8c705",
            LIST,
            "java.util.List",
            "java.util.List",
            null,
            false,
            RESOLVED,
            3,
            FIELD_TYPE,
            1),
        linha(
            "e_89c6da31e6bf2641",
            FUTURE,
            "java.util.concurrent.Future",
            "java.util.concurrent.Future",
            null,
            false,
            RESOLVED,
            2,
            RETURN_TYPE,
            1),
        linha(
            "e_1f70fbaded14170e",
            EXT_LONGO,
            rotuloLongo,
            LONGO,
            null,
            false,
            UNRESOLVED_EXTERNAL,
            1,
            ANNOTATION_REFERENCE,
            1));
  }

  private static final DependencyTotals TOTAIS_DE_ALVO = new DependencyTotals(8, 5, 6);

  @Test
  void oInDeUmTipoEOAgregadoPorParNaOrdemDoRanking() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final DependencyPage page = pagina(mundo.pk(), ALVO, IN, null, 500);

    assertThat(page).isEqualTo(new DependencyPage(inDeAlvo(), null, TOTAIS_DE_ALVO));
  }

  @Test
  void oOutDeUmTipoListaOsInternosEDepoisTodoExterno() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final DependencyPage page = pagina(mundo.pk(), ALVO, OUT, null, 500);

    assertThat(page).isEqualTo(new DependencyPage(outDeAlvo(), null, TOTAIS_DE_ALVO));
  }

  @Test
  void oInPaginaAteOFimSemBuracoNemRepeticao() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    assertThat(paginaAteOFim(mundo, ALVO, IN, 3)).isEqualTo(inDeAlvo());
    assertThat(pagina(mundo.pk(), ALVO, IN, null, 3).next())
        .isEqualTo(new DependencyCursor(IN, mundo.de(ALVO), mundo.de(DELTA)));
  }

  @Test
  void oOutPaginaAteOFimAtravessandoOsDoisBlocos() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final DependencyPage segunda =
        pagina(mundo.pk(), ALVO, OUT, new DependencyCursor(OUT, mundo.de(ALVO), mundo.de(BETA)), 3);

    assertThat(paginaAteOFim(mundo, ALVO, OUT, 3)).isEqualTo(outDeAlvo());
    assertThat(segunda.items()).isEqualTo(outDeAlvo().subList(3, 6));
    assertThat(segunda.next())
        .isEqualTo(new DependencyCursor(OUT, mundo.de(ALVO), mundo.de(STRING)));
  }

  @Test
  void qualquerTamanhoDePaginaDaAMesmaLista() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    for (final int limite : new int[] {1, 2, 4, 7}) {
      assertThat(paginaAteOFim(mundo, ALVO, IN, limite)).as("IN %d", limite).isEqualTo(inDeAlvo());
      assertThat(paginaAteOFim(mundo, ALVO, OUT, limite))
          .as("OUT %d", limite)
          .isEqualTo(outDeAlvo());
    }
  }

  @Test
  void umaPaginaQueTerminaAListaNaoTemCursorMesmoCheia() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final DependencyPage in = pagina(mundo.pk(), ALVO, IN, null, 8);
    final DependencyPage out = pagina(mundo.pk(), ALVO, OUT, null, 11);

    assertThat(in.items()).hasSize(8);
    assertThat(in.next()).isNull();
    assertThat(out.items()).hasSize(11);
    assertThat(out.next()).isNull();
  }

  @Test
  void oCursorDoOutRetomaDentroDoBlocoExterno() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final DependencyPage page =
        pagina(
            mundo.pk(), ALVO, OUT, new DependencyCursor(OUT, mundo.de(ALVO), mundo.de(STRING)), 2);

    assertThat(page)
        .isEqualTo(
            new DependencyPage(
                outDeAlvo().subList(6, 8),
                new DependencyCursor(OUT, mundo.de(ALVO), mundo.de(FOO)),
                TOTAIS_DE_ALVO));
  }

  @Test
  void inEOutPaginamDeFormaIndependente() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final DependencyCursor cursorIn = pagina(mundo.pk(), ALVO, IN, null, 3).next();

    final DependencyPage out = pagina(mundo.pk(), ALVO, OUT, null, 3);
    final DependencyPage in = pagina(mundo.pk(), ALVO, IN, cursorIn, 3);

    assertThat(out.items()).isEqualTo(outDeAlvo().subList(0, 3));
    assertThat(in.items()).isEqualTo(inDeAlvo().subList(3, 6));
  }

  @Test
  void oLacoNaoEstaEmSentidoNenhum() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    final List<DependencyEntry> linhas = new ArrayList<>();
    linhas.addAll(pagina(mundo.pk(), ALVO, IN, null, 500).items());
    linhas.addAll(pagina(mundo.pk(), ALVO, OUT, null, 500).items());

    assertThat(linhas)
        .extracting(DependencyEntry::elementId)
        .doesNotContain(new CodeElementId(ALVO));
    assertThat(linhas).extracting(DependencyEntry::edgeId).doesNotContain(EdgeIds.of(ALVO, ALVO));
  }

  @Test
  void umaLinhaDePaginaEAArestaDesenhadaDoMesmoPar() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final Slice slice =
        cache.read(
            c ->
                GraphSliceQuery.slice(
                    c, mundo.pk(), GraphLevel.TYPE, new CodeElementId(CORE), TYPE_BUDGET));
    final Map<String, SliceEdge> arestas =
        slice.edges().stream().collect(Collectors.toMap(SliceEdge::id, Function.identity()));

    final DependencyPage in = pagina(mundo.pk(), ALVO, IN, null, 500);
    final DependencyPage out = pagina(mundo.pk(), ALVO, OUT, null, 500);

    int comparadas = 0;
    for (final DependencyDirection sentido : DependencyDirection.values()) {
      for (final DependencyEntry linha : (sentido == IN ? in : out).items()) {
        if (!linha.elementId().value().startsWith("type:app.core.")) {
          continue;
        }
        final String origem = sentido == IN ? linha.elementId().value() : ALVO;
        final String destino = sentido == IN ? ALVO : linha.elementId().value();
        assertThat(arestas).as("%s %s", sentido, linha.elementId()).containsKey(linha.edgeId());
        assertThat(arestas.get(linha.edgeId()))
            .isEqualTo(
                new SliceEdge(
                    linha.edgeId(),
                    origem,
                    destino,
                    linha.types(),
                    linha.weight(),
                    linha.resolution()));
        comparadas++;
      }
    }
    assertThat(comparadas).isEqualTo(6);
  }

  @Test
  void umTipoSemDependenciaTemPaginasVaziasETotaisZero() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final DependencyTotals zero = new DependencyTotals(0, 0, 0);

    assertThat(pagina(mundo.pk(), SOZINHO, IN, null, 500))
        .isEqualTo(new DependencyPage(List.of(), null, zero));
    assertThat(pagina(mundo.pk(), SOZINHO, OUT, null, 500))
        .isEqualTo(new DependencyPage(List.of(), null, zero));
  }

  @Test
  void oInDeUmPacoteSaoAsSuasLinhasDePackageEdge() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final DependencyTotals totais = new DependencyTotals(3, 3, null);

    final DependencyPage page = pagina(mundo.pk(), CORE, IN, null, 500);

    assertThat(page)
        .isEqualTo(
            new DependencyPage(
                List.of(
                    linha(
                        "e_770ba3e755a2f380",
                        WEB,
                        "web",
                        "app.web",
                        null,
                        false,
                        INFERRED,
                        4,
                        IMPORTS,
                        1,
                        CALLS,
                        1,
                        FIELD_TYPE,
                        1,
                        PARAMETER_TYPE,
                        2),
                    linha(
                        "e_25511642a07a38c2",
                        API,
                        "api",
                        "app.api",
                        null,
                        false,
                        RESOLVED,
                        2,
                        IMPORTS,
                        2),
                    linha(
                        "e_48f3a2ffd2fdf662",
                        UTIL_PKG,
                        "util",
                        "app.util",
                        null,
                        false,
                        RESOLVED,
                        2,
                        CALLS,
                        1,
                        FIELD_TYPE,
                        9)),
                null,
                totais));
  }

  @Test
  void oOutDeUmPacotePaginaAteOFimSemExternos() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final List<DependencyEntry> esperadas =
        List.of(
            linha(
                "e_fa8bfc74ba362dd2", WEB, "web", "app.web", null, false, RESOLVED, 2, IMPORTS, 2),
            linha(
                "e_802ce57474961abc",
                API,
                "api",
                "app.api",
                null,
                false,
                INFERRED,
                1,
                ANNOTATION_REFERENCE,
                3),
            linha(
                "e_19568146d5a8e048",
                UTIL_PKG,
                "util",
                "app.util",
                null,
                false,
                RESOLVED,
                1,
                PARAMETER_TYPE,
                2,
                RETURN_TYPE,
                1));

    final DependencyPage primeira = pagina(mundo.pk(), CORE, OUT, null, 1);

    assertThat(paginaAteOFim(mundo, CORE, OUT, 1)).isEqualTo(esperadas);
    assertThat(primeira.totals()).isEqualTo(new DependencyTotals(3, 3, null));
    assertThat(primeira.next()).isEqualTo(new DependencyCursor(OUT, mundo.de(CORE), mundo.de(WEB)));
  }

  @Test
  void osTotaisSaoContadosSobreAsLinhasNuncaLidosDoGrafo() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_contagem");
    final long x = a.addPackage("app.x", false, metricasDePacote(), grafo(9, 9, 0));
    final long tipoX = a.addType(x, "X", classe(), metricas(), grafo(99, 77, 55));
    final long y = a.addType(x, "Y", classe(), metricas(), grafo(0, 1, 0));
    final long z = a.addType(x, "Z", classe(), metricas(), grafo(1, 0, 0));
    final long object = a.addExternalType("java.lang.Object");
    final long e = a.addExternalType("ext.E");
    a.addDependency(y, tipoX, CALLS, RESOLVED, 1);
    a.addDependency(tipoX, z, CALLS, RESOLVED, 1);
    a.addDependency(tipoX, object, FIELD_TYPE, RESOLVED, 1);
    a.addDependency(tipoX, e, CALLS, UNRESOLVED_EXTERNAL, 1);
    final long analise = a.write().pk();

    assertThat(pagina(analise, "type:app.x.X", IN, null, 500).totals())
        .isEqualTo(new DependencyTotals(1, 1, 2));
    assertThat(pagina(analise, "type:app.x.X", OUT, null, 500).totals())
        .isEqualTo(new DependencyTotals(1, 1, 2));
    assertThat(pagina(analise, "pkg:app.x", IN, null, 500).totals())
        .isEqualTo(new DependencyTotals(0, 0, null));
  }

  @Test
  void naoHaTetoDeAlcanceTodaLinhaChegaPeloCursor() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_muitos");
    final long pacote = a.addPackage("app.muitos", NodeValues.PLAIN);
    final Map<String, Long> pks = new HashMap<>();
    final List<String> dependentes = new ArrayList<>();
    for (int i = 2_499; i >= 0; i--) {
      final String nome = "T%04d".formatted(i);
      pks.put("type:app.muitos." + nome, a.addType(pacote, nome, NodeValues.PLAIN));
    }
    for (int i = 0; i < 2_500; i++) {
      dependentes.add("type:app.muitos.T%04d".formatted(i));
    }
    final long centro =
        a.addType(pacote, "Centro", new NodeValues(1, 2_500, 0, 0.5, ResolutionMix.NONE));
    pks.put("type:app.muitos.Centro", centro);
    for (final String id : dependentes) {
      a.addDependency(pks.get(id), centro, IMPORTS, RESOLVED, 1);
    }
    final Mundo mundo = new Mundo(a.write().pk(), Map.copyOf(pks));
    final String id = "type:app.muitos.Centro";

    final DependencyPage padrao =
        pagina(mundo.pk(), id, IN, null, AnalysisConstants.DEPENDENCY_PAGE_DEFAULT);
    final List<DependencyEntry> todas =
        paginaAteOFim(mundo, id, IN, AnalysisConstants.DEPENDENCY_PAGE_MAX);

    assertThat(padrao.items()).hasSize(500);
    assertThat(padrao.next())
        .isEqualTo(new DependencyCursor(IN, centro, mundo.de("type:app.muitos.T0499")));
    assertThat(padrao.totals()).isEqualTo(new DependencyTotals(2_500, 0, 0));
    assertThat(todas).extracting(entrada -> entrada.elementId().value()).isEqualTo(dependentes);
  }

  @Test
  void umCursorDoOutroSentidoERecusado() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final long alvo = mundo.de(ALVO);
    final long beta = mundo.de(BETA);

    assertRefused(
        () -> pagina(mundo.pk(), ALVO, OUT, new DependencyCursor(IN, alvo, beta), 3),
        Reason.CURSOR_UNKNOWN);
    assertRefused(
        () -> pagina(mundo.pk(), ALVO, IN, new DependencyCursor(OUT, alvo, beta), 3),
        Reason.CURSOR_UNKNOWN);
  }

  @Test
  void umCursorDeOutroElementoERecusado() throws Exception {
    final Mundo mundo = mundoDep("an_dep");

    assertRefused(
        () ->
            pagina(
                mundo.pk(), ALVO, IN, new DependencyCursor(IN, mundo.de(DELTA), mundo.de(GAMA)), 3),
        Reason.CURSOR_UNKNOWN);
    assertRefused(
        () ->
            pagina(
                mundo.pk(), CORE, IN, new DependencyCursor(IN, mundo.de(ALVO), mundo.de(WEB)), 3),
        Reason.CURSOR_UNKNOWN);
  }

  @Test
  void umaPontaForaDaListaERecusada() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final long alvo = mundo.de(ALVO);

    for (final String ponta : List.of(UTIL, ALVO, STRING, PORTA)) {
      assertRefused(
          () -> pagina(mundo.pk(), ALVO, IN, new DependencyCursor(IN, alvo, mundo.de(ponta)), 3),
          Reason.CURSOR_UNKNOWN);
    }
    for (final String ponta : List.of(ALVO, GAMA, PORTA)) {
      assertRefused(
          () -> pagina(mundo.pk(), ALVO, OUT, new DependencyCursor(OUT, alvo, mundo.de(ponta)), 3),
          Reason.CURSOR_UNKNOWN);
    }
    assertRefused(
        () -> pagina(mundo.pk(), ALVO, IN, new DependencyCursor(IN, alvo, 999_999), 3),
        Reason.CURSOR_UNKNOWN);
    assertRefused(
        () ->
            pagina(
                mundo.pk(), CORE, IN, new DependencyCursor(IN, mundo.de(CORE), mundo.de(ALVO)), 3),
        Reason.CURSOR_UNKNOWN);
  }

  @Test
  void umElementoAusenteOuExternoNaoEEncontrado() throws Exception {
    final Mundo mundo = mundoDep("an_dep");
    final SyntheticAnalysis outra = cache.analysis("an_outra");
    outra.addType(outra.addPackage("app.outra", NodeValues.PLAIN), "Sua", NodeValues.PLAIN);
    outra.write();

    for (final String id :
        List.of(
            "type:app.core.Nada",
            STRING,
            FOO,
            "pkg:app.nada",
            "pkg:java.lang",
            "pkg:",
            "pkg:app.outra",
            "type:app.outra.Sua")) {
      for (final DependencyDirection sentido : DependencyDirection.values()) {
        assertThatThrownBy(() -> pagina(mundo.pk(), id, sentido, null, 500))
            .as("%s %s", id, sentido)
            .isInstanceOf(ElementNotFoundException.class);
      }
    }
  }
}
