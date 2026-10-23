package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.SqlTransactions;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class GraphMetricDaoTest {

  private static final List<String> COLUNAS =
      List.of(
          "granularity",
          "fan_in",
          "fan_out",
          "fan_in_resolved",
          "fan_out_resolved",
          "fan_out_external",
          "external_fan_in",
          "pagerank",
          "pagerank_out",
          "scc_id",
          "scc_size",
          "bridge_span",
          "component_id",
          "internal_cohesion",
          "betweenness",
          "is_entry_point",
          "entry_point_kind");

  private static final String INSERT_CRU =
      "INSERT INTO graph_metric (element_pk, analysis_pk, "
          + String.join(", ", COLUNAS)
          + ") VALUES (?, ?"
          + ", ?".repeat(COLUNAS.size())
          + ")";

  private static final List<ElementRow> SEIS_ELEMENTOS =
      List.of(
          elemento(1, "pkg:a", null),
          elemento(2, "pkg:b", null),
          elemento(3, "type:a.A", 1L),
          elemento(4, "type:a.B", 1L),
          elemento(5, "type:b.C", 2L),
          elemento(6, "type:b.D", 2L));

  private static final List<GraphMetricRow> UMA_DE_CADA =
      List.of(
          pacote(1, 1, 1, 0.1 + 0.2, 3.81e-6, 1, 2, 1, 3, 1.0),
          pacote(2, 1, 0, Double.MIN_NORMAL, 1.0, null, null, 2, 0, null),
          tipo(3, 2, 1, 1, 1, 0, 2, 9.0 / 37.0, 0.15 / 39_364, 1, 3, 3),
          tipo(4, 0, 0, 0, 0, 0, 0, 1.0, Math.nextDown(1.0), null, null, 0),
          tipo(5, 5, 7, 0, 7, 12, 5, Double.MIN_VALUE, 0.5, 2, 2, 4),
          tipo(6, 3, 3, 3, 3, 0, 0, 1.0 / 3.0, 2.0 / 3.0, 2, 2, 0));

  @TempDir private Path temp;

  private static ElementRow elemento(long pk, String elementId, Long pai) {
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
        false,
        false,
        null,
        null,
        null,
        null);
  }

  private static GraphMetricRow tipo(
      long pk,
      int fanIn,
      int fanOut,
      int fanInResolved,
      int fanOutResolved,
      int fanOutExternal,
      int externalFanIn,
      double pagerank,
      double pagerankOut,
      Integer sccId,
      Integer sccSize,
      int bridgeSpan) {
    return new GraphMetricRow(
        pk,
        ElementKind.TYPE,
        fanIn,
        fanOut,
        fanInResolved,
        fanOutResolved,
        fanOutExternal,
        externalFanIn,
        pagerank,
        pagerankOut,
        sccId,
        sccSize,
        bridgeSpan,
        null,
        null,
        null);
  }

  private static GraphMetricRow pacote(
      long pk,
      int fanIn,
      int fanOut,
      double pagerank,
      double pagerankOut,
      Integer sccId,
      Integer sccSize,
      int componentId,
      int internalCohesion,
      Double betweenness) {
    return new GraphMetricRow(
        pk,
        ElementKind.PACKAGE,
        fanIn,
        fanOut,
        null,
        null,
        null,
        null,
        pagerank,
        pagerankOut,
        sccId,
        sccSize,
        null,
        componentId,
        internalCohesion,
        betweenness);
  }

  private static List<ElementRow> pacoteETipos(int quantos) {
    final List<ElementRow> linhas = new ArrayList<>();
    linhas.add(elemento(1, "pkg:p", null));
    for (int i = 0; i < quantos; i++) {
      linhas.add(elemento(i + 2L, "type:p.T" + i, 1L));
    }
    return linhas;
  }

  private static List<GraphMetricRow> linhasDosTipos(int quantos) {
    final List<GraphMetricRow> linhas = new ArrayList<>(quantos);
    for (int i = 0; i < quantos; i++) {
      final int fan = i % 5;
      final Integer ciclo = i % 3 == 0 ? null : 1 + i % 4;
      linhas.add(
          tipo(
              i + 2L,
              fan,
              fan + 1,
              fan / 2,
              fan,
              i % 7,
              fan,
              1.0 / (i + 2),
              1.0 / (i + 3),
              ciclo,
              ciclo == null ? null : 2 + i % 2,
              i % 4));
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
    return Long.parseLong(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM graph_metric"));
  }

  private static Map<String, String> colunasCruas(Connection banco, long elementPk)
      throws SQLException {
    final StringBuilder sql = new StringBuilder("SELECT ");
    for (final String coluna : COLUNAS) {
      sql.append("typeof(").append(coluna).append(") || ':' || coalesce(");
      sql.append(coluna).append(", 'NULL'), ");
    }
    sql.setLength(sql.length() - 2);
    sql.append(" FROM graph_metric WHERE element_pk = ?");
    final Map<String, String> colunas = new LinkedHashMap<>();
    try (PreparedStatement consulta = banco.prepareStatement(sql.toString())) {
      consulta.setLong(1, elementPk);
      try (ResultSet linha = consulta.executeQuery()) {
        assertThat(linha.next()).as("a linha " + elementPk).isTrue();
        for (int i = 0; i < COLUNAS.size(); i++) {
          colunas.put(COLUNAS.get(i), linha.getString(i + 1));
        }
      }
    }
    return colunas;
  }

  private static List<String> bits(List<GraphMetricRow> linhas) {
    final List<String> todos = new ArrayList<>();
    for (final GraphMetricRow linha : linhas) {
      todos.add(
          linha.elementPk()
              + " "
              + Long.toHexString(Double.doubleToRawLongBits(linha.pagerank()))
              + " "
              + Long.toHexString(Double.doubleToRawLongBits(linha.pagerankOut()))
              + " "
              + (linha.betweenness() == null
                  ? "NULL"
                  : Long.toHexString(Double.doubleToRawLongBits(linha.betweenness()))));
    }
    return todos;
  }

  @Test
  void gravaELeTodasAsColunasEONuloContinuaNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      GraphMetricDao.insert(banco, analise, UMA_DE_CADA);

      assertThat(GraphMetricDao.list(banco, analise)).isEqualTo(UMA_DE_CADA);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(element_pk || ':' || analysis_pk || ':' || granularity"
                      + " || ':' || typeof(fan_in_resolved) || ':' || typeof(component_id)"
                      + " || ':' || typeof(scc_id) || ':' || typeof(betweenness)"
                      + " || ':' || typeof(is_entry_point) || ':' || typeof(entry_point_kind), ' ')"
                      + " FROM (SELECT * FROM graph_metric ORDER BY element_pk)"))
          .isEqualTo(
              "1:1:PACKAGE:null:integer:integer:real:null:null"
                  + " 2:1:PACKAGE:null:integer:null:null:null:null"
                  + " 3:1:TYPE:integer:null:integer:null:null:null"
                  + " 4:1:TYPE:integer:null:null:null:null:null"
                  + " 5:1:TYPE:integer:null:integer:null:null:null"
                  + " 6:1:TYPE:integer:null:integer:null:null:null");
    }
  }

  @Test
  void osDoublesVoltamComOsMesmosBits() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      GraphMetricDao.insert(banco, analise, UMA_DE_CADA);

      assertThat(bits(GraphMetricDao.list(banco, analise))).isEqualTo(bits(UMA_DE_CADA));
    }
  }

  @Test
  void cadaValorVaiParaASuaColuna() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      GraphMetricDao.insert(
          banco,
          analise,
          List.of(
              pacote(1, 30, 29, 0.5, 0.0625, 28, 27, 26, 25, 24.5),
              tipo(3, 20, 19, 18, 17, 16, 15, 0.25, 0.125, 14, 13, 12)));

      assertThat(colunasCruas(banco, 1))
          .containsExactlyEntriesOf(
              linhaEsperada(
                  "text:PACKAGE",
                  "integer:30",
                  "integer:29",
                  "null:NULL",
                  "null:NULL",
                  "null:NULL",
                  "null:NULL",
                  "real:0.5",
                  "real:0.0625",
                  "integer:28",
                  "integer:27",
                  "null:NULL",
                  "integer:26",
                  "integer:25",
                  "real:24.5",
                  "null:NULL",
                  "null:NULL"));
      assertThat(colunasCruas(banco, 3))
          .containsExactlyEntriesOf(
              linhaEsperada(
                  "text:TYPE",
                  "integer:20",
                  "integer:19",
                  "integer:18",
                  "integer:17",
                  "integer:16",
                  "integer:15",
                  "real:0.25",
                  "real:0.125",
                  "integer:14",
                  "integer:13",
                  "integer:12",
                  "null:NULL",
                  "null:NULL",
                  "null:NULL",
                  "null:NULL",
                  "null:NULL"));
    }
  }

  private static Map<String, String> linhaEsperada(String... valores) {
    final Map<String, String> esperado = new LinkedHashMap<>();
    for (int i = 0; i < COLUNAS.size(); i++) {
      esperado.put(COLUNAS.get(i), valores[i]);
    }
    return esperado;
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int quantas) throws Exception {
    final List<GraphMetricRow> esperadas = linhasDosTipos(quantas);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(quantas));

      GraphMetricDao.insert(banco, analise, esperadas);

      assertThat(GraphMetricDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(contar(banco)).isEqualTo(quantas);
    }
  }

  @Test
  void aListaEDeUmaAnaliseSoPorChaveCrescente() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);
      final long segunda =
          analiseComElementos(
              banco, "an_02", List.of(elemento(7, "pkg:a", null), elemento(8, "type:a.A", 7L)));
      final List<GraphMetricRow> daSegunda =
          List.of(
              pacote(7, 0, 0, 0.5, 0.5, null, null, 1, 0, 0.0),
              tipo(8, 0, 0, 0, 0, 0, 0, 0.5, 0.5, null, null, 0));
      GraphMetricDao.insert(banco, segunda, daSegunda);
      GraphMetricDao.insert(banco, primeira, UMA_DE_CADA);

      assertThat(GraphMetricDao.list(banco, primeira)).isEqualTo(UMA_DE_CADA);
      assertThat(GraphMetricDao.list(banco, segunda)).isEqualTo(daSegunda);
    }
  }

  @Test
  void umElementPkRepetidoFalhaAChamadaENaoEntraNaTabela() throws Exception {
    final List<GraphMetricRow> linhas = new ArrayList<>(linhasDosTipos(700));
    linhas.set(650, tipo(12, 1, 1, 1, 1, 0, 0, 0.5, 0.5, null, null, 0));

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(700));
      banco.setAutoCommit(false);
      try {
        assertThatThrownBy(() -> GraphMetricDao.insert(banco, analise, linhas))
            .satisfies(
                failure ->
                    assertThat(DaoTestDatabase.resultCode(failure))
                        .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
        assertThat(contar(banco)).isEqualTo(AnalysisConstants.ROWS_PER_INSERT);

        banco.rollback();

        assertThat(contar(banco)).isZero();
      } finally {
        banco.setAutoCommit(true);
      }
    }
  }

  @Test
  void umaLinhaSemElementoFalhaPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(
              () ->
                  GraphMetricDao.insert(
                      banco, analise, List.of(tipo(99, 0, 0, 0, 0, 0, 0, 0.5, 0.5, null, null, 0))))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
      assertThat(contar(banco)).isZero();
    }
  }

  @Test
  void semCancelamentoGravaTudoEConsultaAntesDeCadaPedaco() throws Exception {
    final int linhas = 2 * AnalysisConstants.ROWS_PER_INSERT + 1;
    final AtomicInteger consultas = new AtomicInteger();

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(linhas));

      GraphMetricDao.insert(
          banco,
          analise,
          linhasDosTipos(linhas),
          () -> {
            consultas.incrementAndGet();
            return false;
          });

      assertThat(contar(banco)).isEqualTo(linhas);
      assertThat(consultas).hasValueGreaterThanOrEqualTo(3);
    }
  }

  @Test
  void umCancelamentoAntesDoSegundoPedacoDesfazATransacaoInteira() throws Exception {
    final int linhas = AnalysisConstants.ROWS_PER_INSERT + 1;
    final AtomicInteger consultas = new AtomicInteger();
    final AtomicLong naSegundaConsulta = new AtomicLong(-1);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", pacoteETipos(linhas));

      assertThatThrownBy(
              () ->
                  SqlTransactions.run(
                      banco,
                      c -> {
                        GraphMetricDao.insert(
                            c,
                            analise,
                            linhasDosTipos(linhas),
                            () -> {
                              if (consultas.incrementAndGet() < 2) {
                                return false;
                              }
                              naSegundaConsulta.compareAndSet(-1, contarSemFalha(c));
                              return true;
                            });
                        return null;
                      }))
          .isInstanceOf(CancellationException.class);

      assertThat(naSegundaConsulta)
          .as("o primeiro pedaço inteiro estava na transação quando o cancelamento chegou")
          .hasValue(AnalysisConstants.ROWS_PER_INSERT);
      assertThat(contar(banco)).as("a transação inteira foi desfeita").isZero();
      assertThat(banco.getAutoCommit()).isTrue();
    }
  }

  private static long contarSemFalha(Connection banco) {
    try {
      return contar(banco);
    } catch (SQLException falha) {
      throw new IllegalStateException(falha);
    }
  }

  private static Map<String, Object> tipoValido() {
    final Map<String, Object> valores = new LinkedHashMap<>();
    valores.put("granularity", "TYPE");
    valores.put("fan_in", 2);
    valores.put("fan_out", 2);
    valores.put("fan_in_resolved", 1);
    valores.put("fan_out_resolved", 1);
    valores.put("fan_out_external", 0);
    valores.put("external_fan_in", 1);
    valores.put("pagerank", 0.25);
    valores.put("pagerank_out", 0.25);
    valores.put("scc_id", null);
    valores.put("scc_size", null);
    valores.put("bridge_span", 1);
    valores.put("component_id", null);
    valores.put("internal_cohesion", null);
    valores.put("betweenness", null);
    valores.put("is_entry_point", null);
    valores.put("entry_point_kind", null);
    return Collections.unmodifiableMap(valores);
  }

  private static Map<String, Object> pacoteValido() {
    final Map<String, Object> valores = new LinkedHashMap<>(tipoValido());
    valores.put("granularity", "PACKAGE");
    valores.put("fan_in", 1);
    valores.put("fan_out", 1);
    valores.put("fan_in_resolved", null);
    valores.put("fan_out_resolved", null);
    valores.put("fan_out_external", null);
    valores.put("external_fan_in", null);
    valores.put("pagerank", 0.5);
    valores.put("pagerank_out", 0.5);
    valores.put("bridge_span", null);
    valores.put("component_id", 1);
    valores.put("internal_cohesion", 0);
    valores.put("betweenness", 0.0);
    return Collections.unmodifiableMap(valores);
  }

  private static Map<String, Object> com(Map<String, Object> base, Object... pares) {
    final Map<String, Object> valores = new LinkedHashMap<>(base);
    for (int i = 0; i < pares.length; i += 2) {
      assertThat(valores).as("a coluna existe").containsKey((String) pares[i]);
      valores.put((String) pares[i], pares[i + 1]);
    }
    return Collections.unmodifiableMap(valores);
  }

  private static GraphMetricRow comoRecord(long pk, Map<String, Object> v) {
    return new GraphMetricRow(
        pk,
        ElementKind.valueOf((String) v.get("granularity")),
        (Integer) v.get("fan_in"),
        (Integer) v.get("fan_out"),
        (Integer) v.get("fan_in_resolved"),
        (Integer) v.get("fan_out_resolved"),
        (Integer) v.get("fan_out_external"),
        (Integer) v.get("external_fan_in"),
        (Double) v.get("pagerank"),
        (Double) v.get("pagerank_out"),
        (Integer) v.get("scc_id"),
        (Integer) v.get("scc_size"),
        (Integer) v.get("bridge_span"),
        (Integer) v.get("component_id"),
        (Integer) v.get("internal_cohesion"),
        (Double) v.get("betweenness"));
  }

  private static void inserirCru(
      Connection banco, long analise, long elementPk, Map<String, Object> valores)
      throws SQLException {
    final Object[] ligados = new Object[COLUNAS.size() + 2];
    ligados[0] = elementPk;
    ligados[1] = analise;
    for (int i = 0; i < COLUNAS.size(); i++) {
      ligados[i + 2] = valores.get(COLUNAS.get(i));
    }
    DaoTestDatabase.execute(banco, INSERT_CRU, ligados);
  }

  static Stream<Arguments> formasIlegais() {
    final Map<String, Object> t = tipoValido();
    final Map<String, Object> p = pacoteValido();
    final SQLiteErrorCode check = SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK;
    return Stream.of(
        Arguments.of("granularidade METHOD", com(t, "granularity", "METHOD"), check),
        Arguments.of("fan_in negativo", com(p, "fan_in", -1), check),
        Arguments.of("fan_out negativo", com(p, "fan_out", -1), check),
        Arguments.of("pagerank zero", com(t, "pagerank", 0.0), check),
        Arguments.of("pagerank negativo", com(t, "pagerank", -0.25), check),
        Arguments.of("pagerank acima de um", com(t, "pagerank", Math.nextUp(1.0)), check),
        Arguments.of("pagerank infinito", com(t, "pagerank", Double.POSITIVE_INFINITY), check),
        Arguments.of(
            "pagerank NaN",
            com(t, "pagerank", Double.NaN),
            SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL),
        Arguments.of("pagerank_out zero", com(p, "pagerank_out", 0.0), check),
        Arguments.of("pagerank_out acima de um", com(p, "pagerank_out", 2.0), check),
        Arguments.of(
            "pagerank_out NaN",
            com(p, "pagerank_out", Double.NaN),
            SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL),
        Arguments.of("scc_id sem scc_size", com(t, "scc_id", 1), check),
        Arguments.of("scc_size sem scc_id", com(p, "scc_size", 2), check),
        Arguments.of("scc_id zero", com(t, "scc_id", 0, "scc_size", 2), check),
        Arguments.of("scc_size um", com(p, "scc_id", 1, "scc_size", 1), check),
        Arguments.of(
            "TYPE sem as colunas de tipo",
            com(
                t,
                "fan_in_resolved",
                null,
                "fan_out_resolved",
                null,
                "fan_out_external",
                null,
                "external_fan_in",
                null,
                "bridge_span",
                null),
            check),
        Arguments.of("TYPE sem fan_out_resolved", com(t, "fan_out_resolved", null), check),
        Arguments.of("TYPE sem fan_out_external", com(t, "fan_out_external", null), check),
        Arguments.of("TYPE sem external_fan_in", com(t, "external_fan_in", null), check),
        Arguments.of("TYPE sem bridge_span", com(t, "bridge_span", null), check),
        Arguments.of(
            "TYPE com component_id", com(t, "component_id", 1, "internal_cohesion", 0), check),
        Arguments.of("TYPE com internal_cohesion", com(t, "internal_cohesion", 0), check),
        Arguments.of("TYPE com betweenness", com(t, "betweenness", 0.0), check),
        Arguments.of(
            "PACKAGE sem as colunas de pacote",
            com(p, "component_id", null, "internal_cohesion", null, "betweenness", null),
            check),
        Arguments.of("PACKAGE sem internal_cohesion", com(p, "internal_cohesion", null), check),
        Arguments.of(
            "PACKAGE com as colunas de tipo",
            com(
                p,
                "fan_in_resolved",
                0,
                "fan_out_resolved",
                0,
                "fan_out_external",
                0,
                "external_fan_in",
                0,
                "bridge_span",
                0),
            check),
        Arguments.of("PACKAGE com bridge_span", com(p, "bridge_span", 0), check),
        Arguments.of("PACKAGE com fan_out_external", com(p, "fan_out_external", 0), check),
        Arguments.of("fan_in_resolved acima de fan_in", com(t, "fan_in_resolved", 3), check),
        Arguments.of("fan_in_resolved negativo", com(t, "fan_in_resolved", -1), check),
        Arguments.of("fan_out_resolved acima de fan_out", com(t, "fan_out_resolved", 3), check),
        Arguments.of("fan_out_resolved negativo", com(t, "fan_out_resolved", -1), check),
        Arguments.of("fan_out_external negativo", com(t, "fan_out_external", -1), check),
        Arguments.of("external_fan_in acima de fan_in", com(t, "external_fan_in", 3), check),
        Arguments.of("external_fan_in negativo", com(t, "external_fan_in", -1), check),
        Arguments.of("bridge_span negativo", com(t, "bridge_span", -1), check),
        Arguments.of("component_id zero", com(p, "component_id", 0), check),
        Arguments.of("internal_cohesion negativo", com(p, "internal_cohesion", -1), check),
        Arguments.of("betweenness negativa", com(p, "betweenness", -0.5), check));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formasIlegais")
  void oRecordRecusaCadaFormaIlegal(
      String caso, Map<String, Object> valores, SQLiteErrorCode ignorado) {
    assertThatThrownBy(() -> comoRecord(3, valores))
        .as(caso)
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formasIlegais")
  void oCheckDaTabelaRecusaAMesmaFormaIlegal(
      String caso, Map<String, Object> valores, SQLiteErrorCode esperado) throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(() -> inserirCru(banco, analise, 3, valores))
          .as(caso)
          .satisfies(
              failure -> assertThat(DaoTestDatabase.resultCode(failure)).isEqualTo(esperado));
      assertThat(contar(banco)).isZero();
    }
  }

  @Test
  void asFormasValidasPassamPelosChecks() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      inserirCru(banco, analise, 3, tipoValido());
      inserirCru(banco, analise, 1, pacoteValido());

      assertThat(contar(banco)).isEqualTo(2);
      assertThat(comoRecord(3, tipoValido()).granularity()).isEqualTo(ElementKind.TYPE);
      assertThat(comoRecord(1, pacoteValido()).granularity()).isEqualTo(ElementKind.PACKAGE);
    }
  }

  static Stream<Arguments> formasSoDoSql() {
    final Map<String, Object> t = tipoValido();
    final SQLiteErrorCode check = SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK;
    final SQLiteErrorCode notNull = SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL;
    return Stream.of(
        Arguments.of("granularity NULL", com(t, "granularity", null), notNull),
        Arguments.of("granularidade inventada", com(t, "granularity", "MODULE"), check),
        Arguments.of("fan_in NULL", com(t, "fan_in", null), notNull),
        Arguments.of("fan_out NULL", com(t, "fan_out", null), notNull),
        Arguments.of("pagerank NULL", com(t, "pagerank", null), notNull),
        Arguments.of("pagerank_out NULL", com(t, "pagerank_out", null), notNull),
        Arguments.of("is_entry_point 2", com(t, "is_entry_point", 2), check),
        Arguments.of(
            "tipo de entrada em um não ponto",
            com(t, "is_entry_point", 0, "entry_point_kind", "MAIN"),
            check),
        Arguments.of("ponto de entrada sem tipo", com(t, "is_entry_point", 1), check),
        Arguments.of(
            "tipo de entrada sem a coluna de ponto de entrada",
            com(t, "is_entry_point", null, "entry_point_kind", "MAIN"),
            check));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formasSoDoSql")
  void oSqlRecusaOQueORecordNaoRepresenta(
      String caso, Map<String, Object> valores, SQLiteErrorCode esperado) throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(() -> inserirCru(banco, analise, 3, valores))
          .as(caso)
          .satisfies(
              failure -> assertThat(DaoTestDatabase.resultCode(failure)).isEqualTo(esperado));
      assertThat(contar(banco)).isZero();
    }
  }

  @Test
  void umaIntermediacaoNaNSoORecordRecusaEOInfinitoOSqlTambem() throws Exception {
    final Map<String, Object> infinita =
        com(pacoteValido(), "betweenness", Double.POSITIVE_INFINITY);
    final Map<String, Object> nan = com(pacoteValido(), "betweenness", Double.NaN);
    assertThatThrownBy(() -> comoRecord(1, infinita)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> comoRecord(1, nan)).isInstanceOf(IllegalArgumentException.class);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComElementos(banco, "an_01", SEIS_ELEMENTOS);

      assertThatThrownBy(() -> inserirCru(banco, analise, 1, infinita))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      assertThat(contar(banco)).isZero();
      inserirCru(banco, analise, 1, nan);
      assertThat(DaoTestDatabase.scalar(banco, "SELECT typeof(betweenness) FROM graph_metric"))
          .isEqualTo("null");
    }
  }
}
