package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.SqlTransactions;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class PackageEdgeDaoTest {

  private static final String INSERT_CRU =
      "INSERT INTO package_edge (source_pk, target_pk, analysis_pk, type_pair_count,"
          + " occurrence_total, resolution, imports, extends, implements, calls, field_type,"
          + " parameter_type, return_type, annotation_reference)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final List<ElementRow> TRES_PACOTES =
      List.of(pacote(1, "pkg:a"), pacote(2, "pkg:b"), pacote(3, "pkg:c"));

  private static final List<PackageEdgeRow> QUATRO_ARESTAS =
      List.of(
          aresta(1, 2, 1, ResolutionQuality.RESOLVED, ocorrencias(DependencyType.CALLS, 4)),
          aresta(
              1,
              3,
              2,
              ResolutionQuality.INFERRED,
              ocorrencias(
                  DependencyType.IMPORTS, 1,
                  DependencyType.FIELD_TYPE, 2,
                  DependencyType.PARAMETER_TYPE, 3)),
          aresta(
              2,
              1,
              3,
              ResolutionQuality.RESOLVED,
              ocorrencias(
                  DependencyType.IMPORTS, 1,
                  DependencyType.EXTENDS, 2,
                  DependencyType.IMPLEMENTS, 3,
                  DependencyType.CALLS, 4,
                  DependencyType.FIELD_TYPE, 5,
                  DependencyType.PARAMETER_TYPE, 6,
                  DependencyType.RETURN_TYPE, 7,
                  DependencyType.ANNOTATION_REFERENCE, 8)),
          aresta(
              3,
              2,
              1,
              ResolutionQuality.INFERRED,
              ocorrencias(DependencyType.ANNOTATION_REFERENCE, 1)));

  @TempDir private Path temp;

  private static ElementRow pacote(long pk, String elementId) {
    final CodeElementId id = new CodeElementId(elementId);
    final String nome = elementId.substring(elementId.indexOf(':') + 1);
    return new ElementRow(
        pk,
        id,
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

  private static List<ElementRow> pacotes(int quantos) {
    final List<ElementRow> linhas = new ArrayList<>(quantos);
    for (int i = 0; i < quantos; i++) {
      linhas.add(pacote(i + 1L, "pkg:p" + i));
    }
    return linhas;
  }

  private static Map<DependencyType, Integer> ocorrencias(Object... pares) {
    final Map<DependencyType, Integer> mapa = new EnumMap<>(DependencyType.class);
    for (int i = 0; i < pares.length; i += 2) {
      mapa.put((DependencyType) pares[i], (Integer) pares[i + 1]);
    }
    return mapa;
  }

  private static int soma(Map<DependencyType, Integer> ocorrencias) {
    return ocorrencias.values().stream().mapToInt(Integer::intValue).sum();
  }

  private static PackageEdgeRow aresta(
      long origem,
      long destino,
      int pares,
      ResolutionQuality qualidade,
      Map<DependencyType, Integer> ocorrencias) {
    return new PackageEdgeRow(origem, destino, pares, soma(ocorrencias), qualidade, ocorrencias);
  }

  private static List<PackageEdgeRow> todasAsArestas(int pacotes) {
    final List<PackageEdgeRow> linhas = new ArrayList<>();
    final DependencyType[] tipos = tiposDeAresta();
    for (int origem = 1; origem <= pacotes; origem++) {
      for (int destino = 1; destino <= pacotes; destino++) {
        if (origem == destino) {
          continue;
        }
        final int pares = 1 + (origem + destino) % 4;
        linhas.add(
            aresta(
                origem,
                destino,
                pares,
                destino % 2 == 0 ? ResolutionQuality.RESOLVED : ResolutionQuality.INFERRED,
                ocorrencias(tipos[(origem * 7 + destino) % tipos.length], pares + origem % 3)));
      }
    }
    return linhas;
  }

  private static DependencyType[] tiposDeAresta() {
    return Stream.of(DependencyType.values())
        .filter(DependencyType::isTypeEdge)
        .toArray(DependencyType[]::new);
  }

  private static long analiseComPacotes(Connection banco, String analysisId, List<ElementRow> p)
      throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    ElementDao.insert(banco, analise, p);
    return analise;
  }

  private static long contar(Connection banco, String tabela) throws SQLException {
    return Long.parseLong(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM " + tabela));
  }

  @Test
  void gravaELeAsArestasComSoOsTiposPresentes() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", TRES_PACOTES);

      PackageEdgeDao.insert(banco, analise, QUATRO_ARESTAS);

      final List<PackageEdgeRow> lidas = PackageEdgeDao.list(banco, analise);
      assertThat(lidas).isEqualTo(QUATRO_ARESTAS);
      assertThat(lidas.get(0).occurrences())
          .as("um tipo ausente não volta como 0: volta ausente")
          .containsOnlyKeys(DependencyType.CALLS);
    }
  }

  @Test
  void cadaTipoVaiParaASuaColunaEOAusenteEZero() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", TRES_PACOTES);

      PackageEdgeDao.insert(banco, analise, QUATRO_ARESTAS);

      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(source_pk || '>' || target_pk || ':' || analysis_pk || ':'"
                      + " || type_pair_count || ':' || occurrence_total || ':' || resolution"
                      + " || ':' || imports || ',' || extends || ',' || implements || ','"
                      + " || calls || ',' || field_type || ',' || parameter_type || ','"
                      + " || return_type || ',' || annotation_reference, ' ')"
                      + " FROM (SELECT * FROM package_edge ORDER BY source_pk, target_pk)"))
          .isEqualTo(
              "1>2:1:1:4:RESOLVED:0,0,0,4,0,0,0,0"
                  + " 1>3:1:2:6:INFERRED:1,0,0,0,2,3,0,0"
                  + " 2>1:1:3:36:RESOLVED:1,2,3,4,5,6,7,8"
                  + " 3>2:1:1:1:INFERRED:0,0,0,0,0,0,0,1");
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT count(*) FROM package_edge WHERE typeof(imports) <> 'integer'"
                      + " OR typeof(extends) <> 'integer' OR typeof(implements) <> 'integer'"
                      + " OR typeof(calls) <> 'integer' OR typeof(field_type) <> 'integer'"
                      + " OR typeof(parameter_type) <> 'integer' OR typeof(return_type) <> 'integer'"
                      + " OR typeof(annotation_reference) <> 'integer'"))
          .isEqualTo("0");
    }
  }

  @ParameterizedTest(name = "{0} pacotes")
  @ValueSource(ints = {0, 2, 22, 23, 33})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int quantos) throws Exception {
    final List<PackageEdgeRow> esperadas = todasAsArestas(quantos);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", pacotes(Math.max(quantos, 1)));

      PackageEdgeDao.insert(banco, analise, esperadas);

      assertThat(PackageEdgeDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(contar(banco, "package_edge")).isEqualTo(esperadas.size());
    }
  }

  @Test
  void aListaEDeUmaAnaliseSo() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComPacotes(banco, "an_01", TRES_PACOTES);
      final long segunda =
          analiseComPacotes(banco, "an_02", List.of(pacote(4, "pkg:a"), pacote(5, "pkg:b")));
      final List<PackageEdgeRow> daSegunda =
          List.of(
              aresta(5, 4, 1, ResolutionQuality.RESOLVED, ocorrencias(DependencyType.CALLS, 1)));
      PackageEdgeDao.insert(banco, segunda, daSegunda);
      PackageEdgeDao.insert(banco, primeira, QUATRO_ARESTAS);

      assertThat(PackageEdgeDao.list(banco, primeira)).isEqualTo(QUATRO_ARESTAS);
      assertThat(PackageEdgeDao.list(banco, segunda)).isEqualTo(daSegunda);
    }
  }

  @Test
  void umParRepetidoFalhaAChamadaPelaChave() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", TRES_PACOTES);
      final PackageEdgeRow umaVez =
          aresta(1, 2, 1, ResolutionQuality.RESOLVED, ocorrencias(DependencyType.CALLS, 1));

      assertThatThrownBy(() -> PackageEdgeDao.insert(banco, analise, List.of(umaVez, umaVez)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
      assertThat(contar(banco, "package_edge")).isZero();
    }
  }

  @Test
  void umCancelamentoAntesDoSegundoPedacoDesfazAsDuasTabelas() throws Exception {
    final int quantos = 23;
    final List<PackageEdgeRow> arestas = todasAsArestas(quantos);
    assertThat(arestas).hasSizeGreaterThan(AnalysisConstants.ROWS_PER_INSERT);
    final List<GraphMetricRow> metricas = new ArrayList<>();
    for (int pk = 1; pk <= quantos; pk++) {
      metricas.add(
          new GraphMetricRow(
              pk,
              ElementKind.PACKAGE,
              quantos - 1,
              quantos - 1,
              null,
              null,
              null,
              null,
              1.0 / quantos,
              1.0 / quantos,
              1,
              quantos,
              null,
              1,
              0,
              0.0));
    }
    final AtomicInteger consultas = new AtomicInteger();
    final AtomicLong naSegundaConsulta = new AtomicLong(-1);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", pacotes(quantos));

      assertThatThrownBy(
              () ->
                  SqlTransactions.run(
                      banco,
                      c -> {
                        GraphMetricDao.insert(c, analise, metricas);
                        PackageEdgeDao.insert(
                            c,
                            analise,
                            arestas,
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
      assertThat(contar(banco, "package_edge")).isZero();
      assertThat(contar(banco, "graph_metric"))
          .as("as linhas de métrica da mesma transação também foram desfeitas")
          .isZero();
    }
  }

  private static long contarSemFalha(Connection banco) {
    try {
      return contar(banco, "package_edge");
    } catch (SQLException falha) {
      throw new IllegalStateException(falha);
    }
  }

  static Stream<Arguments> formasIlegaisDoRecord() {
    final Map<DependencyType, Integer> umaChamada = ocorrencias(DependencyType.CALLS, 1);
    final Map<DependencyType, Integer> comNulo = new EnumMap<>(DependencyType.class);
    comNulo.put(DependencyType.CALLS, null);
    return Stream.of(
        caso(
            "aresta de um pacote para ele mesmo",
            () -> new PackageEdgeRow(1, 1, 1, 1, ResolutionQuality.RESOLVED, umaChamada)),
        caso(
            "UNRESOLVED_EXTERNAL",
            () ->
                new PackageEdgeRow(1, 2, 1, 1, ResolutionQuality.UNRESOLVED_EXTERNAL, umaChamada)),
        caso(
            "MODULE_DEPENDENCY não é aresta de tipo",
            () ->
                new PackageEdgeRow(
                    1,
                    2,
                    1,
                    1,
                    ResolutionQuality.RESOLVED,
                    ocorrencias(DependencyType.MODULE_DEPENDENCY, 1))),
        caso(
            "um tipo presente com 0 ocorrência",
            () ->
                new PackageEdgeRow(
                    1,
                    2,
                    1,
                    1,
                    ResolutionQuality.RESOLVED,
                    ocorrencias(DependencyType.CALLS, 1, DependencyType.IMPORTS, 0))),
        caso(
            "um tipo com ocorrência negativa",
            () ->
                new PackageEdgeRow(
                    1,
                    2,
                    1,
                    2,
                    ResolutionQuality.RESOLVED,
                    ocorrencias(DependencyType.CALLS, 3, DependencyType.IMPORTS, -1))),
        caso(
            "uma ocorrência nula",
            () -> new PackageEdgeRow(1, 2, 1, 1, ResolutionQuality.RESOLVED, comNulo)),
        caso(
            "zero pares de tipos",
            () -> new PackageEdgeRow(1, 2, 0, 1, ResolutionQuality.RESOLVED, umaChamada)),
        caso(
            "total acima da soma",
            () -> new PackageEdgeRow(1, 2, 1, 2, ResolutionQuality.RESOLVED, umaChamada)),
        caso(
            "total abaixo da soma",
            () ->
                new PackageEdgeRow(
                    1,
                    2,
                    1,
                    2,
                    ResolutionQuality.RESOLVED,
                    ocorrencias(DependencyType.CALLS, 2, DependencyType.IMPORTS, 1))),
        caso(
            "menos ocorrências que pares",
            () ->
                new PackageEdgeRow(
                    1, 2, 3, 2, ResolutionQuality.RESOLVED, ocorrencias(DependencyType.CALLS, 2))),
        caso(
            "nenhum tipo presente",
            () -> new PackageEdgeRow(1, 2, 1, 0, ResolutionQuality.RESOLVED, ocorrencias())));
  }

  private static Arguments caso(String nome, Supplier<PackageEdgeRow> construcao) {
    return Arguments.of(nome, construcao);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formasIlegaisDoRecord")
  void oRecordRecusaCadaFormaIlegal(String caso, Supplier<PackageEdgeRow> construcao) {
    assertThatThrownBy(construcao::get).as(caso).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oRecordRecusaQualidadeOuMapaNulos() {
    assertThatThrownBy(
            () -> new PackageEdgeRow(1, 2, 1, 1, null, ocorrencias(DependencyType.CALLS, 1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PackageEdgeRow(1, 2, 1, 1, ResolutionQuality.RESOLVED, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void oMapaDoRecordEUmaCopiaImutavel() {
    final Map<DependencyType, Integer> mapa = ocorrencias(DependencyType.CALLS, 1);
    final PackageEdgeRow linha = new PackageEdgeRow(1, 2, 1, 1, ResolutionQuality.RESOLVED, mapa);
    mapa.put(DependencyType.IMPORTS, 5);

    assertThat(linha.occurrences()).containsOnlyKeys(DependencyType.CALLS);
    assertThatThrownBy(() -> linha.occurrences().put(DependencyType.IMPORTS, 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          total diferente da soma,  1, 2, 1, 3, RESOLVED,            0, 0, 0, 2, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          aresta para si mesmo,     1, 1, 1, 1, RESOLVED,            0, 0, 0, 1, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          UNRESOLVED_EXTERNAL,      1, 2, 1, 1, UNRESOLVED_EXTERNAL, 0, 0, 0, 1, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          zero pares,               1, 2, 0, 1, RESOLVED,            0, 0, 0, 1, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          total abaixo dos pares,   1, 2, 3, 2, RESOLVED,            0, 0, 0, 2, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          ocorrência negativa,      1, 2, 1, 2, RESOLVED,           -1, 0, 0, 3, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          qualidade inventada,      1, 2, 1, 1, GUESSED,             0, 0, 0, 1, 0, 0, 0, 0, SQLITE_CONSTRAINT_CHECK
          qualidade NULL,           1, 2, 1, 1, NULL,                0, 0, 0, 1, 0, 0, 0, 0, SQLITE_CONSTRAINT_NOTNULL
          calls NULL,               1, 2, 1, 1, RESOLVED,            0, 0, 0, NULL, 1, 0, 0, 0, SQLITE_CONSTRAINT_NOTNULL
          """)
  void cadaCheckRecusaASuaFormaIlegal(
      String caso,
      long origem,
      long destino,
      int pares,
      int total,
      String qualidade,
      Integer imports,
      Integer extendsCount,
      Integer implementsCount,
      Integer calls,
      Integer fieldType,
      Integer parameterType,
      Integer returnType,
      Integer annotationReference,
      SQLiteErrorCode esperado)
      throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", TRES_PACOTES);

      assertThatThrownBy(
              () ->
                  DaoTestDatabase.execute(
                      banco,
                      INSERT_CRU,
                      origem,
                      destino,
                      analise,
                      pares,
                      total,
                      qualidade,
                      imports,
                      extendsCount,
                      implementsCount,
                      calls,
                      fieldType,
                      parameterType,
                      returnType,
                      annotationReference))
          .as(caso)
          .satisfies(
              failure -> assertThat(DaoTestDatabase.resultCode(failure)).isEqualTo(esperado));
      assertThat(contar(banco, "package_edge")).isZero();
    }
  }

  @Test
  void aFormaValidaPassaPelosChecks() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComPacotes(banco, "an_01", TRES_PACOTES);

      DaoTestDatabase.execute(
          banco, INSERT_CRU, 1, 2, analise, 2, 3, "INFERRED", 1, 0, 0, 2, 0, 0, 0, 0);

      assertThat(contar(banco, "package_edge")).isEqualTo(1);
    }
  }
}
