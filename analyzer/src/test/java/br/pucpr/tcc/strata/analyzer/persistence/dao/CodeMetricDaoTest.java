package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class CodeMetricDaoTest {

  private static final List<CodeMetricRow> UMA_DE_CADA =
      List.of(
          new CodeMetricRow(1, 43, 5, 9, 4, null),
          new CodeMetricRow(2, 30, 3, 7, 4, 4),
          new CodeMetricRow(3, 1, 0, 0, 0, 0),
          new CodeMetricRow(4, 5, 2, 3, 2, null),
          new CodeMetricRow(5, 0, 0, null, null, null),
          new CodeMetricRow(6, 41, null, null, null, null));

  private static final List<ElementRow> SEIS_ELEMENTOS =
      List.of(
          elemento(1, "pkg:a", null, false),
          elemento(2, "type:a.A", 1L, false),
          elemento(3, "type:a.Zero", 1L, false),
          elemento(4, "method:a.A#run()", 2L, false),
          elemento(5, "method:a.A#abstrato()", 2L, false),
          elemento(6, "file:src/main/java/a/A.java", 1L, false));

  private static final String INSERT_CRU =
      "INSERT INTO code_metric (element_pk, analysis_pk, loc, unit_count, cc_sum, cc_max, cc_p90)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

  @TempDir private Path temp;

  private static ElementRow elemento(long pk, String elementId, Long pai, boolean externo) {
    final CodeElementId id = new CodeElementId(elementId);
    final String nome = elementId.substring(elementId.indexOf(':') + 1);
    return new ElementRow(
        pk,
        id,
        id.kind(),
        pai,
        nome,
        nome,
        nome,
        id.kind() == ElementKind.TYPE ? TypeKind.CLASS : null,
        "",
        externo,
        false,
        null,
        null,
        null,
        null);
  }

  private static List<ElementRow> pacoteETipos(int quantos) {
    final List<ElementRow> linhas = new ArrayList<>();
    linhas.add(elemento(1, "pkg:p", null, false));
    for (int i = 2; i <= quantos; i++) {
      linhas.add(elemento(i, "type:p.T" + i, 1L, false));
    }
    return linhas;
  }

  private static List<CodeMetricRow> metricasDosTipos(int ultima) {
    final List<CodeMetricRow> linhas = new ArrayList<>();
    for (int pk = 2; pk <= ultima; pk++) {
      final int unidades = pk % 3;
      linhas.add(
          unidades == 0
              ? new CodeMetricRow(pk, pk % 7, 0, 0, 0, 0)
              : new CodeMetricRow(pk, pk % 7, unidades, unidades + 1, 2, 2));
    }
    return linhas;
  }

  private static long analiseComElementos(Connection banco, String analysisId, List<ElementRow> e)
      throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    ElementDao.insert(banco, analise, e);
    return analise;
  }

  private static long contar(Connection banco) throws SQLException {
    return Long.parseLong(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM code_metric"));
  }

  @Test
  void gravaELeTodasAsColunasEONuloContinuaNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      CodeMetricDao.insert(banco, analise, UMA_DE_CADA);

      assertThat(CodeMetricDao.list(banco, analise)).isEqualTo(UMA_DE_CADA);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(element_pk || ':' || analysis_pk || ':' || typeof(unit_count)"
                      + " || ':' || typeof(cc_sum) || ':' || typeof(cc_max) || ':' || typeof(cc_p90),"
                      + " ' ') FROM (SELECT * FROM code_metric ORDER BY element_pk)"))
          .isEqualTo(
              "1:1:integer:integer:integer:null 2:1:integer:integer:integer:integer"
                  + " 3:1:integer:integer:integer:integer 4:1:integer:integer:integer:null"
                  + " 5:1:integer:null:null:null 6:1:null:null:null:null");
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int linhas) throws Exception {
    final List<CodeMetricRow> esperadas = metricasDosTipos(linhas + 1);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(linhas + 1));

      CodeMetricDao.insert(banco, analise, esperadas);

      assertThat(CodeMetricDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(contar(banco)).isEqualTo(linhas);
    }
  }

  @Test
  void aListaEDeUmaAnaliseSoPorChaveCrescente() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);
      final long segunda =
          analiseComElementos(banco, "an_02", List.of(elemento(7, "pkg:a", null, false)));
      CodeMetricDao.insert(banco, segunda, List.of(new CodeMetricRow(7, 0, 0, 0, 0, null)));
      CodeMetricDao.insert(banco, primeira, UMA_DE_CADA.reversed());

      assertThat(CodeMetricDao.list(banco, primeira)).isEqualTo(UMA_DE_CADA);
      assertThat(CodeMetricDao.list(banco, segunda))
          .containsExactly(new CodeMetricRow(7, 0, 0, 0, 0, null));
    }
  }

  @Test
  void umElementPkRepetidoFalhaAChamadaENaoEntraNaTabela() throws Exception {
    final List<CodeMetricRow> linhas = new ArrayList<>(metricasDosTipos(701));
    linhas.set(650, new CodeMetricRow(12, 1, 0, 0, 0, 0));

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(701));
      banco.setAutoCommit(false);
      try {
        assertThatThrownBy(() -> CodeMetricDao.insert(banco, analise, linhas))
            .satisfies(
                failure ->
                    assertThat(DaoTestDatabase.resultCode(failure))
                        .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
        assertThat(contar(banco)).isEqualTo(500);

        banco.rollback();

        assertThat(contar(banco)).isZero();
      } finally {
        banco.setAutoCommit(true);
      }
    }
  }

  @Test
  void umaMetricaSemElementoFalhaPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(
              () ->
                  CodeMetricDao.insert(
                      banco, analise, List.of(new CodeMetricRow(99, 1, 0, null, null, null))))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
      assertThat(contar(banco)).isZero();
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          loc NULL,                         NULL, NULL, NULL, NULL, NULL, SQLITE_CONSTRAINT_NOTNULL
          loc negativo,                       -1, NULL, NULL, NULL, NULL, SQLITE_CONSTRAINT_CHECK
          unit_count negativo,                 1,   -1, NULL, NULL, NULL, SQLITE_CONSTRAINT_CHECK
          cc_sum sem cc_max,                   1,    1,    1, NULL, NULL, SQLITE_CONSTRAINT_CHECK
          cc_max sem cc_sum,                   1,    1, NULL,    1, NULL, SQLITE_CONSTRAINT_CHECK
          cc_sum sem unit_count,               1, NULL,    1,    1, NULL, SQLITE_CONSTRAINT_CHECK
          cc_sum abaixo de unit_count,         1,    3,    2,    1, NULL, SQLITE_CONSTRAINT_CHECK
          cc_max negativo,                     1,    0,    0,   -1, NULL, SQLITE_CONSTRAINT_CHECK
          cc_max acima de cc_sum,              1,    1,    2,    3, NULL, SQLITE_CONSTRAINT_CHECK
          cc_max vezes unit_count abaixo,      1,    2,    5,    2, NULL, SQLITE_CONSTRAINT_CHECK
          cc_p90 sem cc_max,                   1,    0, NULL, NULL,    1, SQLITE_CONSTRAINT_CHECK
          cc_p90 negativo,                     1,    1,    1,    1,   -1, SQLITE_CONSTRAINT_CHECK
          cc_p90 acima de cc_max,              1,    1,    2,    2,    3, SQLITE_CONSTRAINT_CHECK
          cc_p90 zero com unidades,            1,    2,    2,    1,    0, SQLITE_CONSTRAINT_CHECK
          """)
  void cadaCheckRecusaASuaFormaIlegal(
      String caso,
      Integer loc,
      Integer unitCount,
      Integer ccSum,
      Integer ccMax,
      Integer ccP90,
      SQLiteErrorCode esperado)
      throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(
              () ->
                  DaoTestDatabase.execute(
                      banco, INSERT_CRU, 2, analise, loc, unitCount, ccSum, ccMax, ccP90))
          .as(caso)
          .satisfies(
              failure -> assertThat(DaoTestDatabase.resultCode(failure)).isEqualTo(esperado));
      assertThat(contar(banco)).isZero();
    }
  }

  @Test
  void oStrictRecusaUmPercentilInterpolado() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(
              () -> DaoTestDatabase.execute(banco, INSERT_CRU, 2, analise, 30, 2, 5, 3, 2.5))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_DATATYPE));
    }
  }

  @Test
  void asFormasLegaisDeCadaTipoDeElementoPassamPelosChecks() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      for (final CodeMetricRow linha : UMA_DE_CADA) {
        DaoTestDatabase.execute(
            banco,
            INSERT_CRU,
            linha.elementPk(),
            analise,
            linha.loc(),
            linha.unitCount(),
            linha.ccSum(),
            linha.ccMax(),
            linha.ccP90());
      }

      assertThat(CodeMetricDao.list(banco, analise)).isEqualTo(UMA_DE_CADA);
    }
  }

  private static final List<ElementRow> ARVORE =
      List.of(
          elemento(1, "pkg:a", null, false),
          elemento(2, "type:a.A", 1L, false),
          elemento(3, "type:a.A$Nested", 1L, false),
          elemento(4, "method:a.A#run()", 2L, false),
          elemento(5, "type:x.Ext", 1L, true),
          elemento(6, "file:src/main/java/a/A.java", 1L, false),
          elemento(7, "file:src/main/java/a/package-info.java", 1L, false),
          elemento(8, "file:src/main/java/a/Huge.java", 1L, false),
          elemento(9, "pkg:b", null, false),
          elemento(10, "type:b.B", 9L, false),
          elemento(11, "type:b.Vazio", 9L, false),
          elemento(12, "pkg:c", null, false),
          elemento(13, "file:src/main/java/c/C.java", 12L, false),
          elemento(14, "pkg:d", null, false),
          elemento(15, "file:src/main/java/module-info.java", null, false));

  private static final List<CodeMetricRow> METRICAS_DA_ARVORE =
      List.of(
          new CodeMetricRow(2, 30, 3, 7, 4, 4),
          new CodeMetricRow(3, 8, 2, 2, 1, 1),
          new CodeMetricRow(4, 5, 1, 4, 4, null),
          new CodeMetricRow(5, 0, 9, 90, 50, 50),
          new CodeMetricRow(6, 41, null, null, null, null),
          new CodeMetricRow(7, 2, null, null, null, null),
          new CodeMetricRow(10, 10, 1, 1, 1, 1),
          new CodeMetricRow(11, 1, 0, 0, 0, 0),
          new CodeMetricRow(13, 3, null, null, null, null),
          new CodeMetricRow(15, 4, null, null, null, null));

  @Test
  void aSomaDosPacotesLeOsFileEOsTypeInternosDoPacoteEZeroSemNenhum() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", ARVORE);
      CodeMetricDao.insert(banco, analise, METRICAS_DA_ARVORE);

      assertThat(CodeMetricDao.rollUpPackages(banco, analise)).isEqualTo(4);

      assertThat(CodeMetricDao.list(banco, analise))
          .filteredOn(linha -> List.of(1L, 9L, 12L, 14L).contains(linha.elementPk()))
          .containsExactly(
              new CodeMetricRow(1, 43, 5, 9, 4, null),
              new CodeMetricRow(9, 0, 1, 1, 1, null),
              new CodeMetricRow(12, 3, 0, 0, 0, null),
              new CodeMetricRow(14, 0, 0, 0, 0, null));
      assertThat(contar(banco)).isEqualTo(METRICAS_DA_ARVORE.size() + 4L);
    }
  }

  @Test
  void aSomaDosPacotesEDeUmaAnaliseSo() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComElementos(banco, "an_01", ARVORE);
      CodeMetricDao.insert(banco, primeira, METRICAS_DA_ARVORE);
      final long segunda =
          analiseComElementos(
              banco,
              "an_02",
              List.of(
                  elemento(16, "pkg:a", null, false),
                  elemento(17, "file:src/main/java/a/A.java", 16L, false)));
      CodeMetricDao.insert(
          banco, segunda, List.of(new CodeMetricRow(17, 100, null, null, null, null)));

      assertThat(CodeMetricDao.rollUpPackages(banco, segunda)).isEqualTo(1);

      assertThat(CodeMetricDao.list(banco, segunda))
          .containsExactly(
              new CodeMetricRow(16, 100, 0, 0, 0, null),
              new CodeMetricRow(17, 100, null, null, null, null));
      assertThat(CodeMetricDao.list(banco, primeira)).isEqualTo(METRICAS_DA_ARVORE);
    }
  }

  @Test
  void umaSegundaSomaDosMesmosPacotesFalhaPelaChave() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", ARVORE);
      CodeMetricDao.insert(banco, analise, METRICAS_DA_ARVORE);
      CodeMetricDao.rollUpPackages(banco, analise);

      assertThatThrownBy(() -> CodeMetricDao.rollUpPackages(banco, analise))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
    }
  }
}
