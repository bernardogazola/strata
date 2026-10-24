package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class ElementDaoTest {

  private static final ElementRow PACOTE_A =
      new ElementRow(
          1,
          new CodeElementId("pkg:a"),
          ElementKind.PACKAGE,
          null,
          "a",
          "a",
          "a",
          null,
          "",
          false,
          false,
          null,
          null,
          null,
          null);
  private static final ElementRow ORDER =
      new ElementRow(
          2,
          new CodeElementId("type:a.Order"),
          ElementKind.TYPE,
          1L,
          "Order",
          "a.Order",
          "a.Order",
          TypeKind.CLASS,
          "public final",
          false,
          false,
          1L,
          1L,
          3,
          40);
  private static final ElementRow CONSTRUTOR =
      new ElementRow(
          3,
          new CodeElementId("method:a.Order#<init>()"),
          ElementKind.METHOD,
          2L,
          "<init>",
          "a.Order#<init>",
          "a.Order#<init>()",
          null,
          "",
          false,
          false,
          1L,
          1L,
          null,
          null);
  private static final ElementRow ARQUIVO =
      new ElementRow(
          4,
          new CodeElementId("file:src/main/java/a/Order.java"),
          ElementKind.FILE,
          1L,
          "Order.java",
          "src/main/java/a/Order.java",
          "src/main/java/a/Order.java",
          null,
          "",
          false,
          true,
          1L,
          1L,
          null,
          null);
  private static final ElementRow PACOTE_PADRAO =
      new ElementRow(
          5,
          new CodeElementId("pkg:"),
          ElementKind.PACKAGE,
          null,
          "",
          "",
          "",
          null,
          "",
          false,
          false,
          null,
          null,
          null,
          null);

  @TempDir private Path temp;

  private static long analiseComRaizEArquivo(Connection banco, String analysisId)
      throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    final long raiz = SourceRootDao.nextId(banco);
    final long arquivo = SourceFileDao.nextId(banco);
    SourceRootDao.insert(
        banco,
        analise,
        List.of(
            new SourceRootRow(
                raiz, new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 1)));
    SourceFileDao.insert(
        banco,
        analise,
        List.of(
            new SourceFileRow(
                arquivo, "src/main/java/a/Order.java", 10, 1L, SourceFileStatus.ANALYZED)));
    return analise;
  }

  private static List<ElementRow> pacoteETipos(long primeira, int quantas) {
    final List<ElementRow> linhas = new ArrayList<>();
    if (quantas == 0) {
      return linhas;
    }
    linhas.add(
        new ElementRow(
            primeira,
            new CodeElementId("pkg:a"),
            ElementKind.PACKAGE,
            null,
            "a",
            "a",
            "a",
            null,
            "",
            false,
            false,
            null,
            null,
            null,
            null));
    for (int i = 1; i < quantas; i++) {
      linhas.add(
          new ElementRow(
              primeira + i,
              new CodeElementId("type:a.T" + i),
              ElementKind.TYPE,
              primeira,
              "T" + i,
              "a.T" + i,
              "a.T" + i,
              TypeKind.CLASS,
              "public",
              false,
              false,
              1L,
              1L,
              i,
              i + 1));
    }
    return linhas;
  }

  private static long contar(Connection banco) throws SQLException {
    return Long.parseLong(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM code_element"));
  }

  @Test
  void numUmBancoVazioOPrimeiroIdEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(ElementDao.nextId(banco)).isEqualTo(1);
    }
  }

  @Test
  void gravaELeTodasAsColunasComOsNulosComoNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComRaizEArquivo(banco, "an_01");

      ElementDao.insert(
          banco, analise, List.of(PACOTE_A, ORDER, CONSTRUTOR, ARQUIVO, PACOTE_PADRAO));

      assertThat(ElementDao.list(banco, analise))
          .containsExactly(PACOTE_A, ORDER, CONSTRUTOR, ARQUIVO, PACOTE_PADRAO);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(kind || ':' || coalesce(type_kind, 'NULL') || ':' || sort_key"
                      + " || ':' || coalesce(start_line, 'NULL'), ' ' ORDER BY id)"
                      + " FROM code_element"))
          .isEqualTo(
              "PACKAGE:NULL:a:NULL TYPE:CLASS:a.Order:3 METHOD:NULL:a.Order#<init>():NULL"
                  + " FILE:NULL:src/main/java/a/Order.java:NULL PACKAGE:NULL::NULL");
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int linhas) throws Exception {
    final List<ElementRow> esperadas = pacoteETipos(1, linhas);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComRaizEArquivo(banco, "an_01");

      ElementDao.insert(banco, analise, esperadas);

      assertThat(ElementDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(ElementDao.nextId(banco)).isEqualTo(linhas + 1L);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT count(*) FROM code_element WHERE search_name = lower(simple_name)"))
          .isEqualTo(Integer.toString(linhas));
    }
  }

  @Test
  void oNomeDeBuscaEhODobradoDoNomeSimplesSoNoPacoteENoTipoInterno() throws Exception {
    final ElementRow acao =
        new ElementRow(
            6,
            new CodeElementId("type:a.Ação"),
            ElementKind.TYPE,
            1L,
            "Ação",
            "a.Ação",
            "a.Ação",
            TypeKind.ENUM,
            "",
            false,
            false,
            1L,
            1L,
            null,
            null);
    final ElementRow externo =
        new ElementRow(
            7,
            new CodeElementId("type:java.util.UUID"),
            ElementKind.TYPE,
            null,
            "UUID",
            "java.util.UUID",
            "java.util.UUID",
            null,
            "",
            true,
            false,
            null,
            null,
            null,
            null);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComRaizEArquivo(banco, "an_01");

      ElementDao.insert(
          banco,
          analise,
          List.of(PACOTE_A, ORDER, CONSTRUTOR, ARQUIVO, PACOTE_PADRAO, acao, externo));

      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(element_id || '=' || quote(search_name), ' ' ORDER BY id)"
                      + " FROM code_element"))
          .isEqualTo(
              "pkg:a='a' type:a.Order='order' method:a.Order#<init>()=NULL"
                  + " file:src/main/java/a/Order.java=NULL pkg:='' type:a.Ação='acao'"
                  + " type:java.util.UUID=NULL");
      assertThat(List.of(PACOTE_A, ORDER, CONSTRUTOR, ARQUIVO, PACOTE_PADRAO, acao, externo))
          .extracting(ElementRow::searchName)
          .containsExactly("a", "order", null, null, "", "acao", null);
    }
  }

  @Test
  void oProximoIdContinuaDepoisDasLinhasDeOutraAnalise() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComRaizEArquivo(banco, "an_01");
      ElementDao.insert(banco, primeira, List.of(PACOTE_A, ORDER, CONSTRUTOR));
      final long segunda = analiseComRaizEArquivo(banco, "an_02");

      assertThat(ElementDao.nextId(banco)).isEqualTo(4);
      final ElementRow pacoteDaSegunda =
          new ElementRow(
              4,
              new CodeElementId("pkg:a"),
              ElementKind.PACKAGE,
              null,
              "a",
              "a",
              "a",
              null,
              "",
              false,
              false,
              null,
              null,
              null,
              null);
      final ElementRow tipoDaSegunda =
          new ElementRow(
              5,
              new CodeElementId("type:a.Order"),
              ElementKind.TYPE,
              4L,
              "Order",
              "a.Order",
              "a.Order",
              TypeKind.RECORD,
              "",
              false,
              false,
              2L,
              2L,
              1,
              2);
      ElementDao.insert(banco, segunda, List.of(pacoteDaSegunda, tipoDaSegunda));

      assertThat(ElementDao.list(banco, primeira)).containsExactly(PACOTE_A, ORDER, CONSTRUTOR);
      assertThat(ElementDao.list(banco, segunda)).containsExactly(pacoteDaSegunda, tipoDaSegunda);
      assertThat(ElementDao.nextId(banco)).isEqualTo(6);
    }
  }

  @Test
  void umaSegundaAnaliseNumeradaDeUmFalhaPelaChavePrimaria() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComRaizEArquivo(banco, "an_01");
      ElementDao.insert(banco, primeira, List.of(PACOTE_A));
      final long segunda = analiseComRaizEArquivo(banco, "an_02");

      assertThatThrownBy(() -> ElementDao.insert(banco, segunda, List.of(PACOTE_A)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
    }
  }

  @Test
  void umaDuplicataFalhaAChamadaInteiraENaoEntraNaTabela() throws Exception {
    final List<ElementRow> linhas = new ArrayList<>(pacoteETipos(1, 700));
    linhas.set(
        650,
        new ElementRow(
            651,
            new CodeElementId("type:a.T10"),
            ElementKind.TYPE,
            1L,
            "T10",
            "a.T10",
            "a.T10",
            TypeKind.CLASS,
            "",
            false,
            false,
            1L,
            1L,
            1,
            1));

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComRaizEArquivo(banco, "an_01");
      banco.setAutoCommit(false);
      try {
        assertThatThrownBy(() -> ElementDao.insert(banco, analise, linhas))
            .satisfies(
                failure ->
                    assertThat(DaoTestDatabase.resultCode(failure))
                        .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
        assertThat(
                DaoTestDatabase.scalar(
                    banco, "SELECT count(*) FROM code_element WHERE element_id = ?", "type:a.T10"))
            .isEqualTo("1");
        assertThat(contar(banco)).isEqualTo(500);

        banco.rollback();

        assertThat(contar(banco)).isZero();
      } finally {
        banco.setAutoCommit(true);
      }
    }
  }

  @Test
  void umaLinhaQueNenhumElementoTemNaoChegaASerConstruida() {
    final CodeElementId tipo = new CodeElementId("type:a.Order");
    final CodeElementId metodo = new CodeElementId("method:a.Order#run()");

    assertThatThrownBy(
            () ->
                new ElementRow(
                    1,
                    tipo,
                    ElementKind.PACKAGE,
                    null,
                    "Order",
                    "a.Order",
                    "a.Order",
                    null,
                    "",
                    false,
                    false,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ElementRow(
                    1,
                    metodo,
                    ElementKind.METHOD,
                    2L,
                    "run",
                    "a.Order#run",
                    "a.Order#run()",
                    TypeKind.CLASS,
                    "",
                    false,
                    false,
                    1L,
                    1L,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ElementRow(
                    1,
                    metodo,
                    ElementKind.METHOD,
                    2L,
                    "run",
                    "a.Order#run",
                    "a.Order#run()",
                    null,
                    "",
                    false,
                    false,
                    1L,
                    1L,
                    4,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void achaAChavePeloIdDentroDaAnalisePedida() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComRaizEArquivo(banco, "an_01");
      ElementDao.insert(banco, primeira, List.of(PACOTE_A, ORDER));
      final long segunda = analiseComRaizEArquivo(banco, "an_02");
      ElementDao.insert(banco, segunda, pacoteETipos(3, 1));

      assertThat(ElementDao.findPk(banco, primeira, new CodeElementId("type:a.Order")))
          .isEqualTo(OptionalLong.of(2));
      assertThat(ElementDao.findPk(banco, primeira, new CodeElementId("pkg:a")))
          .isEqualTo(OptionalLong.of(1));
      assertThat(ElementDao.findPk(banco, segunda, new CodeElementId("pkg:a")))
          .isEqualTo(OptionalLong.of(3));
      assertThat(ElementDao.findPk(banco, segunda, new CodeElementId("type:a.Order"))).isEmpty();
    }
  }

  @Test
  void umPacoteETesteSeETodoTipoFilhoETesteEOsArquivosNaoContam() throws Exception {
    final List<ElementRow> linhas =
        List.of(
            pacote(1, "t"),
            tipo(2, "t.A", 1, true),
            tipo(3, "t.B", 1, true),
            pacote(4, "m"),
            tipo(5, "m.A", 4, false),
            tipo(6, "m.B", 4, true),
            pacote(7, "f"),
            tipo(8, "f.A", 7, false),
            new ElementRow(
                9,
                new CodeElementId("file:src/test/java/f/X.java"),
                ElementKind.FILE,
                7L,
                "X.java",
                "src/test/java/f/X.java",
                "src/test/java/f/X.java",
                null,
                "",
                false,
                true,
                1L,
                1L,
                null,
                null));

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComRaizEArquivo(banco, "an_01");
      ElementDao.insert(banco, primeira, linhas);
      final long segunda = analiseComRaizEArquivo(banco, "an_02");
      ElementDao.insert(banco, segunda, List.of(pacote(10, "t"), tipo(11, "t.A", 10, true)));

      ElementDao.markTestPackages(banco, primeira);

      assertThat(pacotesDeTeste(banco, primeira))
          .containsExactly("pkg:t=true", "pkg:m=false", "pkg:f=false");
      assertThat(pacotesDeTeste(banco, segunda)).containsExactly("pkg:t=false");
    }
  }

  private static ElementRow pacote(long pk, String nome) {
    return new ElementRow(
        pk,
        new CodeElementId("pkg:" + nome),
        ElementKind.PACKAGE,
        null,
        nome,
        nome,
        nome,
        null,
        "",
        false,
        false,
        null,
        null,
        null,
        null);
  }

  private static ElementRow tipo(long pk, String nome, long pacote, boolean teste) {
    return new ElementRow(
        pk,
        new CodeElementId("type:" + nome),
        ElementKind.TYPE,
        pacote,
        nome,
        nome,
        nome,
        TypeKind.CLASS,
        "",
        false,
        teste,
        1L,
        1L,
        1,
        2);
  }

  private static List<String> pacotesDeTeste(Connection banco, long analise) throws SQLException {
    return ElementDao.list(banco, analise).stream()
        .filter(linha -> linha.kind() == ElementKind.PACKAGE)
        .map(linha -> linha.elementId() + "=" + linha.isTest())
        .toList();
  }

  @Test
  void umElementoDeUmaAnaliseInexistenteERecusadoPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> ElementDao.insert(banco, 99, List.of(PACOTE_A)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }

  @Test
  void umPaiQueNaoExisteERecusadoPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComRaizEArquivo(banco, "an_01");

      assertThatThrownBy(() -> ElementDao.insert(banco, analise, List.of(ORDER)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
      assertThat(contar(banco)).isZero();
    }
  }
}
