package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MethodSignaturesTest {

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static String exibe(String metodo, String donoSimples) {
    return MethodSignatures.display(new CodeElementId(metodo), donoSimples);
  }

  @Test
  void osExemplosDaEspecificacao() {
    assertThat(
            exibe(
                "method:br.pucpr.legacy.core.Catalog#add(br.pucpr.legacy.core.CatalogEntry)",
                "Catalog"))
        .isEqualTo("add(CatalogEntry)");
    assertThat(
            exibe(
                "method:br.pucpr.legacy.core.Catalog#<init>(br.pucpr.legacy.core.spi.Store)",
                "Catalog"))
        .isEqualTo("Catalog(Store)");
    assertThat(exibe("method:br.pucpr.legacy.core.Kind#<init>()", "Kind")).isEqualTo("Kind()");
    assertThat(exibe("method:br.pucpr.legacy.api.Main#main(java.lang.String[])", "Main"))
        .isEqualTo("main(String[])");
    assertThat(exibe("method:br.pucpr.orders.Order$Builder#<init>()", "Builder"))
        .isEqualTo("Builder()");
  }

  @Test
  void doisParametrosViramNomesSimplesSeparadosPorVirgulaEEspaco() {
    assertThat(exibe("method:a.b.C#pay(a.b.Order,java.util.UUID)", "C"))
        .isEqualTo("pay(Order, UUID)");
  }

  @Test
  void semParametroSaoSoOsParenteses() {
    assertThat(exibe("method:a.b.C#run()", "C")).isEqualTo("run()");
  }

  @Test
  void vetoresGuardamOsColchetesEPrimitivosFicamInteiros() {
    assertThat(exibe("method:a.b.C#find(java.lang.String,int[],java.lang.String[][],long)", "C"))
        .isEqualTo("find(String, int[], String[][], long)");
    assertThat(exibe("method:br.pucpr.Log#info(java.lang.String,java.lang.Object[])", "Log"))
        .isEqualTo("info(String, Object[])");
  }

  @Test
  void oCifraoDeUmAninhadoFicaNoParametro() {
    assertThat(exibe("method:a.b.C#put(java.util.Map$Entry,a.b.Outer$Inner$Deep)", "C"))
        .isEqualTo("put(Map$Entry, Outer$Inner$Deep)");
  }

  @Test
  void parametroDoPacotePadraoEFicaInteiro() {
    assertThat(exibe("method:Main#run(Main,Main$Item)", "Main")).isEqualTo("run(Main, Main$Item)");
    assertThat(exibe("method:a.b.C#$get()", "C")).isEqualTo("$get()");
  }

  @Test
  void oConstrutorMostraOSimpleNameDoDonoENuncaONomeDoId() {
    assertThat(exibe("method:a.b.Outer$Inner#<init>(int)", "Inner")).isEqualTo("Inner(int)");
    assertThat(exibe("method:a.b.My$Type#<init>()", "My$Type")).isEqualTo("My$Type()");
    assertThat(exibe("method:Main#<init>(java.lang.String[])", "Main")).isEqualTo("Main(String[])");
    assertThat(exibe("method:a.b.C#<init>(a.b.D)", "Zeta")).isEqualTo("Zeta(D)");
  }

  @Test
  void umMetodoComumNaoUsaONomeDoDono() {
    assertThat(exibe("method:a.b.C#init()", "C")).isEqualTo("init()");
  }

  @Test
  void oInicializadorEstaticoFicaLiteral() {
    assertThat(exibe("method:a.b.C#<clinit>()", "C")).isEqualTo("<clinit>()");
  }

  @Test
  void nomesForaDoBmpPassamInteiros() {
    final String nome = Z_MATEMATICO + "ip";

    assertThat(exibe("method:a.b.C#" + nome + "(a.b." + Z_MATEMATICO + ")", "C"))
        .isEqualTo(nome + "(" + Z_MATEMATICO + ")");
  }

  @Test
  void oConstrutorDeUmKiloENoveDoCorpusSaiCortadoEm512() {
    final List<String> simples = new ArrayList<>();
    final List<String> completos = new ArrayList<>();
    for (int i = 0; i < 36; i++) {
      final String nome = "InferenceParameterNumber%02d".formatted(i);
      simples.add(nome);
      completos.add("org.elasticsearch.xpack.ml." + nome);
    }
    final String id =
        "method:org.elasticsearch.xpack.ml.Fabrica#<init>(" + String.join(",", completos) + ")";
    final String inteira = "Fabrica(" + String.join(", ", simples) + ")";
    assertThat(id.getBytes(StandardCharsets.UTF_8).length).isBetween(1_900, 2_000);
    assertThat(inteira).hasSize(1_015);

    final String exibida = exibe(id, "Fabrica");

    assertThat(exibida).isEqualTo(inteira.substring(0, AnalysisConstants.MAX_LABEL_CHARS));
  }

  @Test
  void oCorteEEmPontosDeCodigoSemPartirUmPar() {
    final String id =
        "method:a.b.C#m(" + String.join(",", Collections.nCopies(300, "a." + Z_MATEMATICO)) + ")";
    final String esperado = "m(" + (Z_MATEMATICO + ", ").repeat(170);
    assertThat(esperado.codePointCount(0, esperado.length())).isEqualTo(512);

    final String exibida = exibe(id, "C");

    assertThat(exibida).isEqualTo(esperado);
    assertThat(exibida.codePointCount(0, exibida.length()))
        .isEqualTo(AnalysisConstants.MAX_LABEL_CHARS);
    assertThat(Character.isHighSurrogate(exibida.charAt(exibida.length() - 1))).isFalse();
  }

  @Test
  void oCorteGuardaOParQuandoOUltimoPontoEstaForaDoBmp() {
    final String nome = Z_MATEMATICO.repeat(600);
    final String esperado = nome.substring(0, nome.offsetByCodePoints(0, 512));

    final String exibida = exibe("method:a.b.C#" + nome + "()", "C");

    assertThat(exibida).isEqualTo(esperado);
    assertThat(exibida).hasSize(1_024);
  }

  @Test
  void exatamente512PontosDeCodigoFicamInteiros() {
    final String nome = "x".repeat(510);

    assertThat(exibe("method:a.b.C#" + nome + "()", "C")).isEqualTo(nome + "()");
  }

  @Test
  void doisIdsDiferentesPodemExibirOMesmoTexto() {
    assertThat(exibe("method:a.b.C#of(a.b.Key)", "C"))
        .isEqualTo(exibe("method:a.b.C#of(x.y.Key)", "C"));
  }

  @Test
  void umIdQueNaoEDeMetodoERecusado() {
    for (final String id : List.of("type:a.b.C", "pkg:a.b", "file:src/C.java")) {
      assertThatThrownBy(() -> exibe(id, "C")).as(id).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aExibicaoNaoCarregaPacoteNemPrefixo() {
    final String exibida = exibe("method:com.acme.loja.Pedido#total(com.acme.loja.Item)", "Pedido");

    assertThat(exibida).isEqualTo("total(Item)").doesNotContain("method:", "com.acme", "#");
  }
}
