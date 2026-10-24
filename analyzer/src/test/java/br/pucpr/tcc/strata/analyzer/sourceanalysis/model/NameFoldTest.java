package br.pucpr.tcc.strata.analyzer.sourceanalysis.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class NameFoldTest {

  private enum Esperado {
    CASA,
    NAO_CASA,
    JULGAMENTO_CASA,
    JULGAMENTO_NAO_CASA,
    LACUNA_CONHECIDA
  }

  private record Linha(String consulta, String nome, Esperado esperado, String nota) {}

  private static final String ACAO_NFC = "Ação";
  private static final String ACAO_NFD = "Ação";
  private static final String ACAO_MINUSCULA_NFC = "ação";

  private static final List<Linha> TABELA =
      List.of(
          new Linha("order", "Order", Esperado.CASA, "caixa ASCII"),
          new Linha("ORDER", "OrderService", Esperado.CASA, "caixa ASCII"),
          new Linha("service", "OrderService", Esperado.CASA, "substring"),
          new Linha("acao", ACAO_NFC, Esperado.CASA, "acento do pt-BR"),
          new Linha(ACAO_MINUSCULA_NFC, "AÇÃO", Esperado.CASA, "caixa não ASCII"),
          new Linha("Acao", "ConfiguracaoAcao", Esperado.CASA, "controle"),
          new Linha("sao", "São", Esperado.CASA, "acento do pt-BR"),
          new Linha("são", "SaoPaulo", Esperado.CASA, "acento só na consulta"),
          new Linha("ç", "Preço", Esperado.CASA, "ç sozinho"),
          new Linha("c", "ç", Esperado.CASA, "ç ⇒ c"),
          new Linha(ACAO_MINUSCULA_NFC, ACAO_NFD, Esperado.CASA, "consulta NFC, nome NFD"),
          new Linha(ACAO_NFD, ACAO_NFC, Esperado.CASA, "consulta NFD, nome NFC"),
          new Linha("usuario", "UsuárioDAO", Esperado.CASA, "acento do pt-BR"),
          new Linha("strasse", "Straße", Esperado.LACUNA_CONHECIDA, "ß alemão"),
          new Linha("straße", "STRASSE", Esperado.LACUNA_CONHECIDA, "ß alemão"),
          new Linha("istanbul", "İstanbul", Esperado.JULGAMENTO_CASA, "İ turco"),
          new Linha("i", "ılık", Esperado.JULGAMENTO_CASA, "ı turco sem ponto"),
          new Linha("σ", "Σigma", Esperado.CASA, "grego"),
          new Linha("οδοσ", "ΟΔΟΣ", Esperado.JULGAMENTO_CASA, "sigma final grego"),
          new Linha(
              "ΟΔΟΣ",
              "οδοςService",
              Esperado.JULGAMENTO_CASA,
              "sigma final grego, no meio do nome"),
          new Linha("注文", "注文サービス", Esperado.CASA, "CJK"),
          new Linha("サービス", "注文サービス", Esperado.CASA, "substring CJK"),
          new Linha("𐐨", "𐐀Handler", Esperado.CASA, "caixa Deseret suplementar"),
          new Linha("😀", "Emoji😀", Esperado.CASA, "emoji (não é um identificador válido)"),
          new Linha("ｏｒｄｅｒ", "Order", Esperado.JULGAMENTO_CASA, "entrada de largura total"),
          new Linha("file", "ﬁleHandler", Esperado.JULGAMENTO_CASA, "ligadura ﬁ"),
          new Linha("outer$inner", "Outer$Inner", Esperado.CASA, "$ em um nome"),
          new Linha("$", "Outer$Inner", Esperado.CASA, "$ literal"),
          new Linha("a_b", "axb", Esperado.NAO_CASA, "_ literal"),
          new Linha("a_b", "a_b", Esperado.CASA, "_ literal"),
          new Linha("%", "Order", Esperado.NAO_CASA, "% literal"),
          new Linha("payment", "PaymentGateway", Esperado.CASA, "fixture"),
          new Linha("order", "PaymentGateway", Esperado.NAO_CASA, "fixture: só o rótulo"),
          new Linha(
              "́",
              "Café",
              Esperado.JULGAMENTO_NAO_CASA,
              "consulta = uma marca combinante: dobra para vazio"));

  private static boolean casa(String consulta, String nome) {
    final String dobrada = NameFold.fold(consulta);
    return !dobrada.isEmpty() && NameFold.fold(nome).contains(dobrada);
  }

  private static List<Linha> linhas(Esperado... vereditos) {
    final List<Esperado> aceitos = List.of(vereditos);
    return TABELA.stream().filter(linha -> aceitos.contains(linha.esperado())).toList();
  }

  @Test
  void aTabelaTem34LinhasVinteECincoEstritasENoveDeJulgamento() {
    assertThat(TABELA).hasSize(34);
    assertThat(linhas(Esperado.CASA, Esperado.NAO_CASA)).hasSize(25);
    assertThat(
            linhas(
                Esperado.JULGAMENTO_CASA, Esperado.JULGAMENTO_NAO_CASA, Esperado.LACUNA_CONHECIDA))
        .hasSize(9);
  }

  @Test
  void asVinteECincoLinhasEstritasDaoOVereditoEsperado() {
    final List<String> erradas = new ArrayList<>();
    for (final Linha linha : linhas(Esperado.CASA, Esperado.NAO_CASA)) {
      if (casa(linha.consulta(), linha.nome()) != (linha.esperado() == Esperado.CASA)) {
        erradas.add(linha.nota() + ": " + linha.consulta() + " em " + linha.nome());
      }
    }

    assertThat(erradas).isEmpty();
  }

  @Test
  void asLinhasDeJulgamentoDaoAExpectativaDocumentada() {
    for (final Linha linha : linhas(Esperado.JULGAMENTO_CASA)) {
      assertThat(casa(linha.consulta(), linha.nome())).as(linha.nota()).isTrue();
    }
    for (final Linha linha : linhas(Esperado.JULGAMENTO_NAO_CASA)) {
      assertThat(casa(linha.consulta(), linha.nome())).as(linha.nota()).isFalse();
    }
    assertThat(NameFold.fold("́")).isEmpty();
  }

  @Test
  void oSzEtContraSsEhALacunaConhecidaENaoCasa() {
    for (final Linha linha : linhas(Esperado.LACUNA_CONHECIDA)) {
      assertThat(casa(linha.consulta(), linha.nome())).as(linha.nota()).isFalse();
    }
    assertThat(NameFold.fold("Straße")).isEqualTo("straße");
    assertThat(NameFold.fold("STRASSE")).isEqualTo("strasse");
  }

  @Test
  void asFormasDobradasSaoAsEsperadas() {
    assertThat(NameFold.fold(ACAO_NFC)).isEqualTo("acao");
    assertThat(NameFold.fold(ACAO_NFD)).isEqualTo("acao");
    assertThat(NameFold.fold("İstanbul")).isEqualTo("istanbul");
    assertThat(NameFold.fold("ılık")).isEqualTo("ilik");
    assertThat(NameFold.fold("ΟΔΟΣ")).isEqualTo("οδοσ");
    assertThat(NameFold.fold("οδοσ")).isEqualTo("οδοσ");
    assertThat(NameFold.fold("οδοςService")).isEqualTo("οδοσservice");
    assertThat(NameFold.fold("ｏｒｄｅｒ")).isEqualTo("order");
    assertThat(NameFold.fold("ﬁle")).isEqualTo("file");
    assertThat(NameFold.fold("´")).as("o acento espaçado vira um espaço").isEqualTo(" ");
    assertThat(NameFold.fold("Outer$Inner")).isEqualTo("outer$inner");
    assertThat(NameFold.fold("𐐀x")).isEqualTo("𐐨x");
  }

  @Test
  void nfcENfdDobramParaOMesmoNome() {
    assertThat(Normalizer.isNormalized(ACAO_NFC, Normalizer.Form.NFC)).isTrue();
    assertThat(Normalizer.isNormalized(ACAO_NFD, Normalizer.Form.NFC)).isFalse();
    assertThat(ACAO_NFD).isNotEqualTo(ACAO_NFC);

    assertThat(NameFold.fold(ACAO_NFD)).isEqualTo(NameFold.fold(ACAO_NFC));
    assertThat(NameFold.fold("UsuárioDAO")).isEqualTo(NameFold.fold("UsuárioDAO"));
  }

  @Test
  void umCaractereDeFormatacaoDentroDoNomeSomeNaDobra() {
    assertThat(Character.getType(0x202E)).isEqualTo(Character.FORMAT);
    assertThat(Character.getType(0x200B)).isEqualTo(Character.FORMAT);

    assertThat(NameFold.fold("Or‮der")).isEqualTo("order");
    assertThat(NameFold.fold("Order​Service")).isEqualTo("orderservice");
    assertThat(NameFold.fold("﻿Or‍der­")).isEqualTo("order");
    assertThat(casa("orderservice", "Order​Service")).isTrue();
    assertThat(NameFold.fold("‮​")).as("só formatação dobra para vazio").isEmpty();
  }

  @Test
  void oITurcoDobraParaIComQualquerIdiomaPadrao() {
    final Locale anterior = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr"));
    try {
      assertThat("I".toLowerCase(Locale.getDefault())).as("o controle: tr muda o I").isEqualTo("ı");

      assertThat(NameFold.fold("I")).isEqualTo("i");
      assertThat(NameFold.fold("İ")).isEqualTo("i");
      assertThat(NameFold.fold("ı")).isEqualTo("i");
      assertThat(NameFold.fold("INDEX")).isEqualTo("index");
    } finally {
      Locale.setDefault(anterior);
    }
  }

  @Test
  void oSigmaFinalNoMeioDoNomeDobraSemContexto() {
    assertThat(NameFold.fold("Σ")).isEqualTo("σ");
    assertThat(NameFold.fold("ς")).isEqualTo("σ");
    assertThat(NameFold.fold("ΟΔΟΣ")).isEqualTo("οδοσ");
    assertThat(casa("ΟΔΟΣ", "οδοςService")).isTrue();
    assertThat(casa("οδος", "ΟΔΟΣService")).isTrue();
  }

  @Test
  void aDobraEhIdempotenteNaTabela() {
    for (final Linha linha : TABELA) {
      for (final String texto : List.of(linha.consulta(), linha.nome())) {
        final String dobrada = NameFold.fold(texto);
        assertThat(NameFold.fold(dobrada)).as(linha.nota()).isEqualTo(dobrada);
      }
    }
  }

  @Test
  void aDobraEhIdempotenteEmTodoCodePoint() {
    final List<String> naoIdempotentes = new ArrayList<>();
    int vistos = 0;
    for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        continue;
      }
      vistos++;
      final String dobrada = NameFold.fold(Character.toString(codePoint));
      if (!NameFold.fold(dobrada).equals(dobrada)) {
        naoIdempotentes.add(Integer.toHexString(codePoint));
      }
    }

    assertThat(vistos).isEqualTo(1_112_064);
    assertThat(naoIdempotentes).isEmpty();
  }

  @Test
  void oVazioDobraParaOVazioEONuloEhRecusado() {
    assertThat(NameFold.fold("")).isEmpty();
    assertThatThrownBy(() -> NameFold.fold(null)).isInstanceOf(NullPointerException.class);
  }
}
