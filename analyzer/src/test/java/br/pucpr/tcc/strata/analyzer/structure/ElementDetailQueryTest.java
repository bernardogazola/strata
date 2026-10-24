package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Declaration;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.GraphValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Metrics;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ElementDetailQueryTest {

  private static final String Z_LARGO = Character.toString(0xFF3A);

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final String CAMINHO_NFD =
      "mo" + Character.toString(0x0301) + "dulo/src/main/java/com/acme/loja/dados/Pedido.java";

  private static final String PEDIDO = "type:com.acme.loja.dados.Pedido";
  private static final String LINHA = "type:com.acme.loja.dados.Pedido$Linha";
  private static final String DADOS = "pkg:com.acme.loja.dados";
  private static final String M_PEDIDO = "method:com.acme.loja.dados.Pedido#";

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

  private ElementDetail descreve(long analise, String id) throws SQLException {
    return cache.read(c -> ElementDetailQuery.describe(c, analise, new CodeElementId(id)));
  }

  private static MethodSummary metodo(
      String id, String assinatura, Integer cc, Integer inicio, Integer fim) {
    return new MethodSummary(
        new CodeElementId(id),
        assinatura,
        cc,
        inicio == null ? null : new SourceLines(inicio, fim));
  }

  private static GraphValues grafo(
      int in, int out, double pagerank, Cycle ciclo, ResolutionMix mistura) {
    return new GraphValues(in, out, 0, pagerank, ciclo, mistura);
  }

  private long mundoLoja(String analysisId) throws SQLException {
    final SyntheticAnalysis a = cache.analysis(analysisId);
    final long arquivoPedido = a.addSourceFile(CAMINHO_NFD);
    final long arquivoTeste = a.addSourceFile("web/src/test/java/com/acme/web/PedidoTest.java");
    final long arquivoGerado = a.addSourceFile("gen/com/acme/web/Gerado.java");
    final long arquivoMain = a.addSourceFile("src/Main.java");
    final long web =
        a.addPackage(
            "com.acme.web",
            true,
            new Metrics(40, 2, 3, 2, null),
            grafo(0, 1, 0.25, null, StructureCaches.mix(1, 0, 0)));
    final long dados =
        a.addPackage(
            "com.acme.loja.dados",
            false,
            new Metrics(100, 3, 4, 2, null),
            grafo(1, 2, 0.5, new Cycle(2, 2), StructureCaches.mix(9, 4, 1)));
    a.addPackage(
        "com.acme.loja",
        false,
        new Metrics(5, 0, 0, 0, null),
        grafo(1, 0, 0.125, new Cycle(2, 2), ResolutionMix.NONE));
    final long padrao =
        a.addPackage(
            "", false, new Metrics(3, 1, 1, 1, null), grafo(0, 0, 0.125, null, ResolutionMix.NONE));
    final long pedido =
        a.addType(
            dados,
            "Pedido",
            new Declaration(TypeKind.CLASS, "public abstract", false, arquivoPedido, 3, 40),
            new Metrics(30, 4, 9, 3, 3),
            grafo(2, 3, 0.125, new Cycle(7, 3), StructureCaches.mix(5, 2, 1)));
    final long linha =
        a.addType(
            dados,
            "Pedido$Linha",
            new Declaration(TypeKind.RECORD, "public static", false, arquivoPedido, 42, 50),
            new Metrics(8, 1, 1, 1, 1),
            grafo(1, 0, 0.0625, null, ResolutionMix.NONE));
    a.addType(
        web,
        "PedidoTest",
        new Declaration(TypeKind.CLASS, "", true, arquivoTeste, 1, 12),
        new Metrics(10, 0, 0, 0, 0),
        grafo(0, 1, 0.03125, null, ResolutionMix.NONE));
    a.addType(
        web,
        "Gerado",
        new Declaration(TypeKind.INTERFACE, "public abstract", false, arquivoGerado, null, null),
        new Metrics(0, 0, 0, 0, 0),
        grafo(0, 0, 0.1, null, ResolutionMix.NONE));
    final long main =
        a.addType(
            padrao,
            "Main",
            new Declaration(TypeKind.CLASS, "public", false, arquivoMain, 1, 5),
            new Metrics(5, 2, 4, 2, 2),
            grafo(0, 1, 0.25, null, StructureCaches.mix(1, 0, 0)));
    a.addExternalType("java.util.List");

    a.addMethod(pedido, Z_LARGO + "ap", List.of(), 30, 30, Metrics.withoutCc(1));
    a.addMethod(pedido, "nome", List.of(), null, null, Metrics.withoutCc(0));
    a.addMethod(pedido, "id", List.of(), null, null, Metrics.withoutCc(0));
    a.addMethod(pedido, Z_MATEMATICO + "ip", List.of(), 30, 30, Metrics.withoutCc(1));
    a.addMethod(
        pedido,
        "adicionar",
        List.of("com.acme.loja.dados.Item", "int[]"),
        11,
        11,
        Metrics.oneUnit(1, 3));
    a.addMethod(pedido, "total", List.of(), 11, 20, new Metrics(10, 2, 5, 3, null));
    a.addMethod(pedido, "abstrato", List.of(), 25, 25, Metrics.withoutCc(1));
    a.addMethod(pedido, "<init>", List.of("com.acme.loja.dados.Item"), 5, 9, Metrics.oneUnit(5, 1));
    a.addMethod(linha, "valor", List.of(), null, null, Metrics.withoutCc(0));
    a.addMethod(linha, "<init>", List.of("int"), 42, 42, Metrics.oneUnit(1, 1));
    a.addMethod(main, "<init>", List.of(), null, null, Metrics.withoutCc(0));
    a.addMethod(main, "main", List.of("java.lang.String[]"), 2, 4, Metrics.oneUnit(3, 2));
    return a.write().pk();
  }

  @Test
  void umTipoEDescritoCampoPorCampo() throws Exception {
    final long analise = mundoLoja("an_loja");
    final ElementDetail esperado =
        new ElementDetail(
            new CodeElementId(PEDIDO),
            ElementKind.TYPE,
            TypeKind.CLASS,
            "Pedido",
            "com.acme.loja.dados.Pedido",
            new CodeElementId(DADOS),
            CAMINHO_NFD,
            new SourceLines(3, 40),
            List.of("public", "abstract"),
            false,
            new ElementMetrics(30, 4, 9, 3, 2.3, 3),
            new GraphFacts(2, 3, 0.125, new Cycle(7, 3), new ResolutionMix(5, 2, 1)),
            List.of(
                metodo(M_PEDIDO + "<init>(com.acme.loja.dados.Item)", "Pedido(Item)", 1, 5, 9),
                metodo(
                    M_PEDIDO + "adicionar(com.acme.loja.dados.Item,int[])",
                    "adicionar(Item, int[])",
                    3,
                    11,
                    11),
                metodo(M_PEDIDO + "total()", "total()", 5, 11, 20),
                metodo(M_PEDIDO + "abstrato()", "abstrato()", null, 25, 25),
                metodo(M_PEDIDO + Z_LARGO + "ap()", Z_LARGO + "ap()", null, 30, 30),
                metodo(M_PEDIDO + Z_MATEMATICO + "ip()", Z_MATEMATICO + "ip()", null, 30, 30),
                metodo(M_PEDIDO + "id()", "id()", null, null, null),
                metodo(M_PEDIDO + "nome()", "nome()", null, null, null)),
            8);

    assertThat(descreve(analise, PEDIDO)).isEqualTo(esperado);
  }

  @Test
  void umAninhadoTemRotuloRelativoEConstrutorComONomeDoDono() throws Exception {
    final long analise = mundoLoja("an_loja");
    final ElementDetail esperado =
        new ElementDetail(
            new CodeElementId(LINHA),
            ElementKind.TYPE,
            TypeKind.RECORD,
            "Pedido.Linha",
            "com.acme.loja.dados.Pedido.Linha",
            new CodeElementId(DADOS),
            CAMINHO_NFD,
            new SourceLines(42, 50),
            List.of("public", "static"),
            false,
            new ElementMetrics(8, 1, 1, 1, 1.0, 1),
            new GraphFacts(1, 0, 0.0625, null, ResolutionMix.NONE),
            List.of(
                metodo(LINHA.replace("type:", "method:") + "#<init>(int)", "Linha(int)", 1, 42, 42),
                metodo(
                    LINHA.replace("type:", "method:") + "#valor()", "valor()", null, null, null)),
            2);

    assertThat(descreve(analise, LINHA)).isEqualTo(esperado);
  }

  @Test
  void umTipoDeTesteSemUnidadeTemMediaZeroESemMetodo() throws Exception {
    final long analise = mundoLoja("an_loja");

    assertThat(descreve(analise, "type:com.acme.web.PedidoTest"))
        .isEqualTo(
            new ElementDetail(
                new CodeElementId("type:com.acme.web.PedidoTest"),
                ElementKind.TYPE,
                TypeKind.CLASS,
                "PedidoTest",
                "com.acme.web.PedidoTest",
                new CodeElementId("pkg:com.acme.web"),
                "web/src/test/java/com/acme/web/PedidoTest.java",
                new SourceLines(1, 12),
                List.of(),
                true,
                new ElementMetrics(10, 0, 0, 0, 0.0, 0),
                new GraphFacts(0, 1, 0.03125, null, ResolutionMix.NONE),
                List.of(),
                0));
  }

  @Test
  void umTipoSemPosicaoTemLinhasNulas() throws Exception {
    final long analise = mundoLoja("an_loja");

    final ElementDetail gerado = descreve(analise, "type:com.acme.web.Gerado");

    assertThat(gerado.lines()).isNull();
    assertThat(gerado.typeKind()).isEqualTo(TypeKind.INTERFACE);
    assertThat(gerado.modifiers()).containsExactly("public", "abstract");
    assertThat(gerado.filePath()).isEqualTo("gen/com/acme/web/Gerado.java");
  }

  @Test
  void umTipoDoPacotePadraoEAMediaDasColunasDoTipo() throws Exception {
    final long analise = mundoLoja("an_loja");

    assertThat(descreve(analise, "type:Main"))
        .isEqualTo(
            new ElementDetail(
                new CodeElementId("type:Main"),
                ElementKind.TYPE,
                TypeKind.CLASS,
                "Main",
                "Main",
                new CodeElementId("pkg:"),
                "src/Main.java",
                new SourceLines(1, 5),
                List.of("public"),
                false,
                new ElementMetrics(5, 2, 4, 2, 2.0, 2),
                new GraphFacts(0, 1, 0.25, null, new ResolutionMix(1, 0, 0)),
                List.of(
                    metodo("method:Main#main(java.lang.String[])", "main(String[])", 2, 2, 4),
                    metodo("method:Main#<init>()", "Main()", null, null, null)),
                2));
  }

  @Test
  void umPacoteEDescritoCampoPorCampo() throws Exception {
    final long analise = mundoLoja("an_loja");

    assertThat(descreve(analise, DADOS))
        .isEqualTo(
            new ElementDetail(
                new CodeElementId(DADOS),
                ElementKind.PACKAGE,
                null,
                "loja.dados",
                "com.acme.loja.dados",
                null,
                null,
                null,
                List.of(),
                false,
                new ElementMetrics(100, 3, 4, 2, 1.3, null),
                new GraphFacts(1, 2, 0.5, new Cycle(2, 2), new ResolutionMix(9, 4, 1)),
                List.of(),
                0));
  }

  @Test
  void osOutrosPacotesDoMundo() throws Exception {
    final long analise = mundoLoja("an_loja");

    final ElementDetail web = descreve(analise, "pkg:com.acme.web");
    final ElementDetail loja = descreve(analise, "pkg:com.acme.loja");
    final ElementDetail padrao = descreve(analise, "pkg:");

    assertThat(web.isTest()).isTrue();
    assertThat(web.label()).isEqualTo("web");
    assertThat(web.metrics()).isEqualTo(new ElementMetrics(40, 2, 3, 2, 1.5, null));
    assertThat(web.graph()).isEqualTo(new GraphFacts(0, 1, 0.25, null, new ResolutionMix(1, 0, 0)));
    assertThat(loja.label()).isEqualTo("loja");
    assertThat(loja.metrics()).isEqualTo(new ElementMetrics(5, 0, 0, 0, 0.0, null));
    assertThat(padrao.label()).isEmpty();
    assertThat(padrao.qualifiedName()).isEmpty();
    assertThat(padrao.metrics()).isEqualTo(new ElementMetrics(3, 1, 1, 1, 1.0, null));
  }

  @Test
  void milEUmMetodosDaoMilEMethodTotalMilEUm() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_grande");
    final long pacote = a.addPackage("app", NodeValues.PLAIN);
    final long arquivo = a.addSourceFile("src/app/Grande.java");
    final long grande =
        a.addType(
            pacote,
            "Grande",
            new Declaration(TypeKind.CLASS, "", false, arquivo, 1, 3_000),
            new Metrics(3_000, 1_000, 1_000, 1, 1),
            grafo(0, 0, 0.5, null, ResolutionMix.NONE));
    a.addMethod(grande, "a", List.of(), null, null, Metrics.withoutCc(0));
    for (int i = 0; i < 1_000; i++) {
      a.addMethod(
          grande, "m%04d".formatted(i), List.of(), 2_000 - i, 2_000 - i, Metrics.oneUnit(1, 1));
    }
    final long analise = a.write().pk();
    final List<MethodSummary> esperados = new ArrayList<>();
    for (int i = 999; i >= 0; i--) {
      final String nome = "m%04d".formatted(i);
      esperados.add(
          metodo("method:app.Grande#" + nome + "()", nome + "()", 1, 2_000 - i, 2_000 - i));
    }

    final ElementDetail detalhe = descreve(analise, "type:app.Grande");

    assertThat(detalhe.methodTotal()).isEqualTo(1_001);
    assertThat(detalhe.methods()).hasSize(AnalysisConstants.ELEMENT_METHODS_MAX);
    assertThat(detalhe.methods()).isEqualTo(esperados);
  }

  @Test
  void umElementoAusenteOuExternoNaoEEncontrado() throws Exception {
    final long analise = mundoLoja("an_loja");
    final SyntheticAnalysis outra = cache.analysis("an_outra");
    outra.addType(outra.addPackage("app.outra", NodeValues.PLAIN), "Sua", NodeValues.PLAIN);
    outra.write();

    for (final String id :
        List.of(
            "type:com.acme.Nada",
            "type:java.util.List",
            "pkg:java.util",
            "pkg:com.acme",
            "pkg:app.outra",
            "type:app.outra.Sua")) {
      assertThatThrownBy(() -> descreve(analise, id))
          .as(id)
          .isInstanceOf(ElementNotFoundException.class);
    }
  }

  @Test
  void umTipoSemCodeMetricEDefeitoENaoZero() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_defeito");
    final long pacote = a.addPackage("app.defeito", NodeValues.PLAIN);
    a.addType(
        pacote,
        "SemMetrica",
        new Declaration(TypeKind.CLASS, "", false, a.addSourceFile("SemMetrica.java"), 1, 2),
        null,
        grafo(0, 0, 0.5, null, ResolutionMix.NONE));
    final long analise = a.write().pk();

    assertThatThrownBy(() -> descreve(analise, "type:app.defeito.SemMetrica"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("SemMetrica");
  }

  @Test
  void umTipoSemGraphMetricEDefeito() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_defeito");
    final long pacote = a.addPackage("app.defeito", NodeValues.PLAIN);
    a.addType(
        pacote,
        "SemGrafo",
        new Declaration(TypeKind.CLASS, "", false, a.addSourceFile("SemGrafo.java"), 1, 2),
        new Metrics(1, 0, 0, 0, 0),
        null);
    final long analise = a.write().pk();

    assertThatThrownBy(() -> descreve(analise, "type:app.defeito.SemGrafo"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("SemGrafo");
  }

  @Test
  void aMediaEDecimalExataArredondadaParaCimaAUmaCasa() {
    assertThat(ElementDetailQuery.ccMean(3, 2)).isEqualTo(1.5);
    assertThat(ElementDetailQuery.ccMean(4, 3)).isEqualTo(1.3);
    assertThat(ElementDetailQuery.ccMean(9, 4)).isEqualTo(2.3);
    assertThat(ElementDetailQuery.ccMean(7, 4)).isEqualTo(1.8);
    assertThat(ElementDetailQuery.ccMean(7, 3)).isEqualTo(2.3);
    assertThat(ElementDetailQuery.ccMean(4, 2)).isEqualTo(2.0);
    assertThat(ElementDetailQuery.ccMean(23, 20)).isEqualTo(1.2);
    assertThat(ElementDetailQuery.ccMean(39, 20)).isEqualTo(2.0);
    assertThat(ElementDetailQuery.ccMean(0, 0)).isEqualTo(0.0);
  }

  @Test
  void osEmpatesExatosSobemAUmaCasa() {
    assertThat(ElementDetailQuery.ccMean(1, 20)).isEqualTo(0.1);
    assertThat(ElementDetailQuery.ccMean(13, 20)).isEqualTo(0.7);
    assertThat(ElementDetailQuery.ccMean(47, 20)).isEqualTo(2.4);
  }

  @Test
  void aMediaNuncaPassaPorUmDoubleNemComContagensAcimaDeDoisElevadoACinquentaETres() {
    final long escala = 1L << 53;

    assertThat(ElementDetailQuery.ccMean(23 * escala - 1, 20 * escala)).isEqualTo(1.1);
    assertThat(ElementDetailQuery.ccMean(3 * escala - 1, 20 * escala)).isEqualTo(0.1);
    assertThat(ElementDetailQuery.ccMean(47 * escala - 1, 20 * escala)).isEqualTo(2.3);
  }

  @Test
  void oP90EhAColunaDoP90ENuncaOMaximo() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_p90");
    final long pacote = a.addPackage("app", NodeValues.PLAIN);
    final long arquivo = a.addSourceFile("src/app/Pico.java");
    a.addType(
        pacote,
        "Pico",
        new Declaration(TypeKind.CLASS, "", false, arquivo, 1, 40),
        new Metrics(20, 5, 15, 9, 4),
        grafo(0, 0, 0.5, null, ResolutionMix.NONE));
    final long analise = a.write().pk();

    assertThat(descreve(analise, "type:app.Pico").metrics())
        .isEqualTo(new ElementMetrics(20, 5, 15, 9, 3.0, 4));
  }

  @Test
  void umaContagemNegativaERecusada() {
    assertThatThrownBy(() -> ElementDetailQuery.ccMean(-1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ElementDetailQuery.ccMean(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
