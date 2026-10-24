package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.ANNOTATION_REFERENCE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.EXTENDS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.RETURN_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.EDGE_TYPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import br.pucpr.tcc.strata.analyzer.structure.InvalidStructureRequest.Reason;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Declaration;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.GraphValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Metrics;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceSelectionTest {

  private static final String Z_LARGO = Character.toString(0xFF3A);

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final String ARQUIVO = "src/main/java/p/S.java";

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

  private static MethodRef ref(String metodo) {
    if (metodo == null) {
      return null;
    }
    final CodeElementId id = new CodeElementId(metodo);
    final String dono = metodo.substring("method:".length(), metodo.indexOf('#'));
    return new MethodRef(
        id,
        metodo.substring(metodo.indexOf('#') + 1),
        new CodeElementId("type:" + dono),
        dono.substring(dono.lastIndexOf('.') + 1));
  }

  private static RelationshipEvidence ev(
      DependencyType tipo, Integer linha, String de, String para) {
    return new RelationshipEvidence(tipo, ARQUIVO, linha, RESOLVED, ref(de), ref(para));
  }

  private static RelationshipEvidence ev(DependencyType tipo, int linha) {
    return ev(tipo, linha, null, null);
  }

  private static List<RelationshipEvidence> guardadas(DependencyType tipo, int n) {
    final List<RelationshipEvidence> linhas = new ArrayList<>();
    for (int linha = n; linha >= 1; linha--) {
      linhas.add(ev(tipo, linha));
    }
    return linhas;
  }

  private static List<RelationshipEvidence> primeiras(DependencyType tipo, int k) {
    final List<RelationshipEvidence> linhas = new ArrayList<>();
    for (int linha = 1; linha <= k; linha++) {
      linhas.add(ev(tipo, linha));
    }
    return linhas;
  }

  private static List<RelationshipEvidence> concat(List<List<RelationshipEvidence>> partes) {
    return partes.stream().flatMap(List::stream).toList();
  }

  private static Map<DependencyType, Long> porTipo(List<RelationshipEvidence> linhas) {
    return linhas.stream()
        .collect(
            Collectors.groupingBy(
                RelationshipEvidence::type,
                () -> new EnumMap<>(DependencyType.class),
                Collectors.counting()));
  }

  @Test
  void tresTiposDeVinteDaoDezessete_Dezessete_Dezesseis() {
    final List<RelationshipEvidence> entrada = new ArrayList<>();
    entrada.addAll(guardadas(FIELD_TYPE, 20));
    entrada.addAll(guardadas(IMPORTS, 20));
    entrada.addAll(guardadas(CALLS, 20));

    final List<RelationshipEvidence> fio = EvidenceSelection.select(entrada, 50);

    assertThat(fio)
        .isEqualTo(
            concat(
                List.of(primeiras(IMPORTS, 17), primeiras(CALLS, 17), primeiras(FIELD_TYPE, 16))));
  }

  @Test
  void oitoTiposDeVinteDaoSeteSeteESeisSeis() {
    final List<RelationshipEvidence> entrada = new ArrayList<>();
    for (final DependencyType tipo : EDGE_TYPES.reversed()) {
      entrada.addAll(guardadas(tipo, 20));
    }
    final List<List<RelationshipEvidence>> partes = new ArrayList<>();
    for (final DependencyType tipo : EDGE_TYPES) {
      partes.add(primeiras(tipo, tipo == IMPORTS || tipo == EXTENDS ? 7 : 6));
    }

    final List<RelationshipEvidence> fio = EvidenceSelection.select(entrada, 50);

    assertThat(fio).isEqualTo(concat(partes));
    assertThat(porTipo(fio)).hasSize(8);
  }

  @Test
  void quatroTiposDeVinteDaoTrezeTrezeDozeDoze() {
    final List<RelationshipEvidence> entrada = new ArrayList<>();
    entrada.addAll(guardadas(ANNOTATION_REFERENCE, 20));
    entrada.addAll(guardadas(RETURN_TYPE, 20));
    entrada.addAll(guardadas(CALLS, 20));
    entrada.addAll(guardadas(EXTENDS, 20));

    final List<RelationshipEvidence> fio = EvidenceSelection.select(entrada, 50);

    assertThat(fio)
        .isEqualTo(
            concat(
                List.of(
                    primeiras(EXTENDS, 13),
                    primeiras(CALLS, 13),
                    primeiras(RETURN_TYPE, 12),
                    primeiras(ANNOTATION_REFERENCE, 12))));
  }

  @Test
  void tetoDoisComTresTiposDaUmaDeCadaUmDosDoisPrimeiros() {
    final List<RelationshipEvidence> entrada = new ArrayList<>();
    entrada.addAll(guardadas(FIELD_TYPE, 20));
    entrada.addAll(guardadas(CALLS, 20));
    entrada.addAll(guardadas(IMPORTS, 20));

    assertThat(EvidenceSelection.select(entrada, 2)).containsExactly(ev(IMPORTS, 1), ev(CALLS, 1));
  }

  @Test
  void menosGuardadasQueOTetoDaoTodas() {
    final List<RelationshipEvidence> entrada = List.of(ev(CALLS, 5), ev(IMPORTS, 9));

    assertThat(EvidenceSelection.select(entrada, 50)).containsExactly(ev(IMPORTS, 9), ev(CALLS, 5));
  }

  @Test
  void umTipoEsgotadoCedeASuaVezAosOutros() {
    final List<RelationshipEvidence> entrada = new ArrayList<>();
    entrada.addAll(guardadas(RETURN_TYPE, 20));
    entrada.addAll(guardadas(EXTENDS, 2));
    entrada.addAll(guardadas(PARAMETER_TYPE, 20));
    entrada.addAll(guardadas(CALLS, 20));

    final List<RelationshipEvidence> fio = EvidenceSelection.select(entrada, 50);

    assertThat(fio)
        .isEqualTo(
            concat(
                List.of(
                    primeiras(EXTENDS, 2),
                    primeiras(CALLS, 16),
                    primeiras(PARAMETER_TYPE, 16),
                    primeiras(RETURN_TYPE, 16))));
  }

  @Test
  void dentroDeUmTipoAOrdemEDoConteudo() {
    final String a = "method:p.S#a()";
    final String b = "method:p.S#b()";
    final String zLargo = "method:p.S#" + Z_LARGO + "()";
    final String zMatematico = "method:p.S#" + Z_MATEMATICO + "()";
    final List<RelationshipEvidence> esperadas =
        List.of(
            ev(CALLS, 3, "method:p.S#z()", "method:p.T#z()"),
            ev(CALLS, 10, null, null),
            ev(CALLS, 10, a, null),
            ev(CALLS, 10, a, "method:p.T#y()"),
            ev(CALLS, 10, a, "method:p.T#z()"),
            ev(CALLS, 10, b, null),
            ev(CALLS, 10, zLargo, null),
            ev(CALLS, 10, zMatematico, null),
            ev(CALLS, null, null, null),
            ev(CALLS, null, a, null));
    final List<RelationshipEvidence> entrada = new ArrayList<>(esperadas);
    Collections.reverse(entrada);

    assertThat(EvidenceSelection.select(entrada, 50)).isEqualTo(esperadas);
  }

  @Test
  void oCorteDentroDeUmTipoEPeloConteudo() {
    final List<RelationshipEvidence> entrada =
        List.of(
            ev(CALLS, null, null, null),
            ev(CALLS, 10, "method:p.S#b()", null),
            ev(CALLS, 3, "method:p.S#z()", "method:p.T#z()"),
            ev(CALLS, 10, "method:p.S#a()", "method:p.T#y()"),
            ev(CALLS, 10, null, null),
            ev(CALLS, 10, "method:p.S#a()", null));

    assertThat(EvidenceSelection.select(entrada, 3))
        .containsExactly(
            ev(CALLS, 3, "method:p.S#z()", "method:p.T#z()"),
            ev(CALLS, 10, null, null),
            ev(CALLS, 10, "method:p.S#a()", null));
  }

  @Test
  void evidenciasIguaisFicamAsDuas() {
    final RelationshipEvidence igual = ev(CALLS, 7, "method:p.S#a()", "method:p.T#y()");

    assertThat(EvidenceSelection.select(List.of(igual, igual, ev(CALLS, 1)), 50))
        .containsExactly(ev(CALLS, 1), igual, igual);
  }

  @Test
  void umTetoAbaixoDeUmERecusado() {
    assertThatThrownBy(() -> EvidenceSelection.select(List.of(ev(CALLS, 1)), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static final String CATALOGO = "type:app.loja.Catalogo";
  private static final String ESTOQUE = "type:app.loja.Estoque";
  private static final String NUCLEO = "type:app.base.Base$Nucleo";
  private static final String STRING = "type:java.lang.String";
  private static final String ARQUIVO_CATALOGO = "src/main/java/app/loja/Catalogo.java";

  private static GraphValues grafo() {
    return new GraphValues(1, 1, 0, 0.25, null, ResolutionMix.NONE);
  }

  private static Declaration classe(long arquivo) {
    return new Declaration(TypeKind.CLASS, "public", false, arquivo, 1, 30);
  }

  private long mundoCatalogo(String analysisId) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long arquivoCatalogo = a.addSourceFile(ARQUIVO_CATALOGO);
    final long arquivoEstoque = a.addSourceFile("src/main/java/app/loja/Estoque.java");
    final long arquivoBase = a.addSourceFile("src/main/java/app/base/Base.java");
    final Metrics metricas = new Metrics(10, 1, 1, 1, 1);
    final Metrics metricasDePacote = new Metrics(10, 1, 1, 1, null);
    final long loja = a.addPackage("app.loja", false, metricasDePacote, grafo());
    final long base = a.addPackage("app.base", false, metricasDePacote, grafo());
    final long catalogo = a.addType(loja, "Catalogo", classe(arquivoCatalogo), metricas, grafo());
    final long estoque = a.addType(loja, "Estoque", classe(arquivoEstoque), metricas, grafo());
    a.addType(base, "Base", classe(arquivoBase), metricas, grafo());
    final long nucleo = a.addType(base, "Base$Nucleo", classe(arquivoBase), metricas, grafo());
    final long string = a.addExternalType("java.lang.String");
    final long init =
        a.addMethod(catalogo, "<init>", List.of("app.loja.Estoque"), 7, 9, Metrics.oneUnit(3, 1));
    final long buscar =
        a.addMethod(
            catalogo,
            "buscar",
            List.of("java.lang.String", "int[]"),
            11,
            20,
            Metrics.oneUnit(10, 2));
    final long guardar =
        a.addMethod(estoque, "guardar", List.of("app.loja.Item"), 4, 6, Metrics.oneUnit(3, 1));
    final long far = a.addMethod(nucleo, "far", List.of(), 2, 3, Metrics.oneUnit(2, 1));
    final long campo = a.addDependency(catalogo, estoque, FIELD_TYPE, RESOLVED, 1, false);
    final long parametro = a.addDependency(catalogo, estoque, PARAMETER_TYPE, RESOLVED, 1, false);
    final long chamada = a.addDependency(catalogo, estoque, CALLS, INFERRED, 3, false);
    final long externa = a.addDependency(catalogo, string, FIELD_TYPE, RESOLVED, 1, false);
    final long outra = a.addDependency(catalogo, nucleo, CALLS, RESOLVED, 1, false);
    a.addEvidence(chamada, buscar, null, arquivoCatalogo, null, INFERRED);
    a.addEvidence(parametro, init, null, arquivoCatalogo, 7, RESOLVED);
    a.addEvidence(chamada, buscar, far, arquivoCatalogo, 14, INFERRED);
    a.addEvidence(campo, null, null, arquivoCatalogo, 5, RESOLVED);
    a.addEvidence(chamada, buscar, guardar, arquivoCatalogo, 12, RESOLVED);
    a.addEvidence(externa, null, null, arquivoCatalogo, 6, RESOLVED);
    a.addEvidence(outra, buscar, far, arquivoCatalogo, 3, RESOLVED);
    return a.write().pk();
  }

  private List<RelationshipEvidence> evidencias(long analise, String de, String para, int teto)
      throws SQLException {
    return cache.read(
        c ->
            EvidenceQuery.evidence(
                c, analise, new CodeElementId(de), new CodeElementId(para), teto));
  }

  private static final MethodRef BUSCAR =
      new MethodRef(
          new CodeElementId("method:app.loja.Catalogo#buscar(java.lang.String,int[])"),
          "buscar(String, int[])",
          new CodeElementId(CATALOGO),
          "Catalogo");

  private static final MethodRef CONSTRUTOR =
      new MethodRef(
          new CodeElementId("method:app.loja.Catalogo#<init>(app.loja.Estoque)"),
          "Catalogo(Estoque)",
          new CodeElementId(CATALOGO),
          "Catalogo");

  private static final MethodRef GUARDAR =
      new MethodRef(
          new CodeElementId("method:app.loja.Estoque#guardar(app.loja.Item)"),
          "guardar(Item)",
          new CodeElementId(ESTOQUE),
          "Estoque");

  private static final MethodRef FAR =
      new MethodRef(
          new CodeElementId("method:app.base.Base$Nucleo#far()"),
          "far()",
          new CodeElementId(NUCLEO),
          "Base.Nucleo");

  private static RelationshipEvidence sitio(
      DependencyType tipo,
      Integer linha,
      ResolutionQuality qualidade,
      MethodRef de,
      MethodRef para) {
    return new RelationshipEvidence(tipo, ARQUIVO_CATALOGO, linha, qualidade, de, para);
  }

  @Test
  void aEvidenciaDeUmParNaOrdemDoFio() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    assertThat(evidencias(mundo, CATALOGO, ESTOQUE, 50))
        .containsExactly(
            sitio(CALLS, 12, RESOLVED, BUSCAR, GUARDAR),
            sitio(CALLS, 14, INFERRED, BUSCAR, FAR),
            sitio(CALLS, null, INFERRED, BUSCAR, null),
            sitio(FIELD_TYPE, 5, RESOLVED, null, null),
            sitio(PARAMETER_TYPE, 7, RESOLVED, CONSTRUTOR, null));
  }

  @Test
  void oTetoDaConsultaEPorRodizio() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    assertThat(evidencias(mundo, CATALOGO, ESTOQUE, 2))
        .containsExactly(
            sitio(CALLS, 12, RESOLVED, BUSCAR, GUARDAR),
            sitio(FIELD_TYPE, 5, RESOLVED, null, null));
    assertThat(evidencias(mundo, CATALOGO, ESTOQUE, 1))
        .containsExactly(sitio(CALLS, 12, RESOLVED, BUSCAR, GUARDAR));
  }

  @Test
  void umDestinoExternoEServido() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    assertThat(evidencias(mundo, CATALOGO, STRING, 50))
        .containsExactly(sitio(FIELD_TYPE, 6, RESOLVED, null, null));
  }

  @Test
  void aOutraAnaliseNaoVaza() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");
    final SyntheticAnalysis b = cache.analysis("an_b");
    final long arquivo = b.addSourceFile(ARQUIVO_CATALOGO);
    final long loja = b.addPackage("app.loja", false, new Metrics(1, 0, 0, 0, null), grafo());
    final Metrics metricas = new Metrics(1, 0, 0, 0, 0);
    final long catalogo = b.addType(loja, "Catalogo", classe(arquivo), metricas, grafo());
    final long estoque = b.addType(loja, "Estoque", classe(arquivo), metricas, grafo());
    final long linha = b.addDependency(catalogo, estoque, IMPORTS, RESOLVED, 1, false);
    b.addEvidence(linha, null, null, arquivo, 1, RESOLVED);
    final long outra = b.write().pk();

    assertThat(evidencias(outra, CATALOGO, ESTOQUE, 50))
        .containsExactly(sitio(IMPORTS, 1, RESOLVED, null, null));
    assertThat(evidencias(mundo, CATALOGO, ESTOQUE, 50)).hasSize(5);
  }

  @Test
  void umaPontaAusenteOuUmaOrigemExternaNaoEEncontrada() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    for (final List<String> par :
        List.of(
            List.of("type:app.loja.Nada", ESTOQUE),
            List.of(CATALOGO, "type:app.loja.Nada"),
            List.of(STRING, CATALOGO),
            List.of("type:java.util.List", CATALOGO))) {
      assertThatThrownBy(() -> evidencias(mundo, par.get(0), par.get(1), 50))
          .as("%s", par)
          .isInstanceOf(ElementNotFoundException.class);
    }
  }

  @Test
  void umParSemLinhaERecusado() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    for (final List<String> par : List.of(List.of(ESTOQUE, CATALOGO), List.of(ESTOQUE, STRING))) {
      assertThatThrownBy(() -> evidencias(mundo, par.get(0), par.get(1), 50))
          .as("%s", par)
          .isInstanceOfSatisfying(
              InvalidStructureRequest.class,
              recusa -> {
                assertThat(recusa.reason()).isEqualTo(Reason.PAIR_WITHOUT_DEPENDENCY);
                assertThat(recusa.detail()).isEqualTo("o par não tem dependência");
              });
    }
  }

  @Test
  void osTiposDaEvidenciaSaoOsDasLinhasDoPar() throws Exception {
    final long mundo = mundoCatalogo("an_catalogo");

    assertThat(
            evidencias(mundo, CATALOGO, ESTOQUE, 50).stream()
                .map(RelationshipEvidence::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DependencyType.class))))
        .containsExactly(CALLS, FIELD_TYPE, PARAMETER_TYPE);
  }
}
