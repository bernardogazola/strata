package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphInputDaoTest {

  private static final List<Pattern> PROIBIDAS =
      List.of(
          Pattern.compile("\\bweight\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bgroup\\s+by\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bsum\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bmin\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bmax\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bcount\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bavg\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bjoin\\b", Pattern.CASE_INSENSITIVE));

  private static final String ACIMA_DO_BMP = Character.toString(0x1D400);

  private static final String FIM_DO_BMP = Character.toString(0xFF21);

  private static final int TIPOS_GRANDES = 34;

  @TempDir private Path temp;

  private static Map<String, String> consultas() throws IllegalAccessException {
    final Map<String, String> sql = new LinkedHashMap<>();
    for (final Field campo : GraphInputDao.class.getDeclaredFields()) {
      final int modificadores = campo.getModifiers();
      if (Modifier.isStatic(modificadores)
          && Modifier.isFinal(modificadores)
          && campo.getType() == String.class) {
        campo.setAccessible(true);
        sql.put(campo.getName(), (String) campo.get(null));
      }
    }
    return sql;
  }

  @Test
  void asConsultasNaoLeemPesoENaoAgregam() throws Exception {
    final Map<String, String> sql = consultas();

    assertThat(sql).containsKeys("TYPES", "PACKAGES", "ROWS");
    for (final Map.Entry<String, String> consulta : sql.entrySet()) {
      for (final Pattern proibida : PROIBIDAS) {
        assertThat(proibida.matcher(consulta.getValue()).find())
            .as(consulta.getKey() + " contém " + proibida.pattern() + ":\n" + consulta.getValue())
            .isFalse();
      }
      assertThat(consulta.getValue())
          .as(consulta.getKey() + ": toda consulta é de uma única análise")
          .containsIgnoringCase("analysis_pk = ?");
    }
    assertThat(sql.get("ROWS")).containsIgnoringCase("FROM dependency");
  }

  @Test
  void asLinhasSaemComoDependencySemPeso() {
    assertThat(Arrays.stream(Dependency.class.getRecordComponents()).map(RecordComponent::getName))
        .doesNotContain("weight");
    final RecordComponent linhas =
        Arrays.stream(GraphSource.class.getRecordComponents())
            .filter(componente -> "rows".equals(componente.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(((ParameterizedType) linhas.getGenericType()).getActualTypeArguments())
        .containsExactly(Dependency.class);
  }

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

  private static DependencyRow linha(
      long pk,
      long origem,
      long destino,
      DependencyType tipo,
      ResolutionQuality qualidade,
      int ocorrencias,
      boolean ambigua) {
    return new DependencyRow(
        pk,
        origem,
        destino,
        tipo,
        qualidade,
        ocorrencias,
        DependencyAggregator.weightOf(tipo) * ocorrencias,
        ambigua);
  }

  private static List<ElementRow> elementosDaPrimeira() {
    return List.of(
        elemento(1, "pkg:a", null, false),
        elemento(2, "pkg:", null, false),
        elemento(3, "type:a.Z", 1L, false),
        elemento(4, "type:a.B", 1L, false),
        elemento(5, "type:a.Z$Inner", 1L, false),
        elemento(6, "type:Solo", 2L, false),
        elemento(7, "type:java.lang.String", 1L, true),
        elemento(8, "type:Missing", null, true),
        elemento(9, "method:a.Z#run()", 3L, false),
        elemento(10, "file:src/main/java/a/Z.java", 1L, false),
        elemento(11, "type:a." + ACIMA_DO_BMP, 1L, false),
        elemento(12, "type:a." + FIM_DO_BMP, 1L, false),
        elemento(13, "pkg:b", null, false),
        elemento(14, "type:b.Late", 13L, false));
  }

  private static List<DependencyRow> linhasDaPrimeira() {
    return List.of(
        linha(1, 6, 4, DependencyType.EXTENDS, ResolutionQuality.INFERRED, 1, false),
        linha(2, 3, 4, DependencyType.CALLS, ResolutionQuality.RESOLVED, 3, false),
        linha(3, 14, 3, DependencyType.CALLS, ResolutionQuality.RESOLVED, 2, false),
        linha(4, 3, 3, DependencyType.CALLS, ResolutionQuality.RESOLVED, 2, false),
        linha(5, 5, 3, DependencyType.FIELD_TYPE, ResolutionQuality.INFERRED, 1, false),
        linha(6, 4, 7, DependencyType.PARAMETER_TYPE, ResolutionQuality.RESOLVED, 2, false),
        linha(7, 6, 8, DependencyType.CALLS, ResolutionQuality.UNRESOLVED_EXTERNAL, 1, true),
        linha(8, 3, 4, DependencyType.IMPORTS, ResolutionQuality.RESOLVED, 1, false),
        linha(9, 11, 12, DependencyType.RETURN_TYPE, ResolutionQuality.RESOLVED, 1, false));
  }

  private static long segundaAnalise(Connection banco) throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
    ElementDao.insert(
        banco,
        analise,
        List.of(
            elemento(15, "pkg:a", null, false),
            elemento(16, "type:a.Z", 15L, false),
            elemento(17, "type:a.Outra", 15L, false)));
    DependencyDao.insert(
        banco,
        analise,
        List.of(linha(10, 16, 17, DependencyType.CALLS, ResolutionQuality.RESOLVED, 9, false)));
    return analise;
  }

  private static long primeiraAnalise(Connection banco) throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
    ElementDao.insert(banco, analise, elementosDaPrimeira());
    DependencyDao.insert(banco, analise, linhasDaPrimeira());
    return analise;
  }

  private static CodeElementId id(String valor) {
    return new CodeElementId(valor);
  }

  private static Dependency dependencia(
      String origem, String destino, DependencyType tipo, ResolutionQuality qualidade, int vezes) {
    return new Dependency(id(origem), id(destino), tipo, qualidade, vezes);
  }

  @Test
  void leSoOsTiposInternosComOPacoteDeclaradoEmOrdemDeId() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = primeiraAnalise(banco);
      segundaAnalise(banco);

      final GraphSource lido = GraphInputDao.read(banco, analise, () -> false);

      assertThat(lido.types())
          .containsExactly(
              new GraphTypeRow(6, id("type:Solo"), 2, id("pkg:")),
              new GraphTypeRow(4, id("type:a.B"), 1, id("pkg:a")),
              new GraphTypeRow(3, id("type:a.Z"), 1, id("pkg:a")),
              new GraphTypeRow(5, id("type:a.Z$Inner"), 1, id("pkg:a")),
              new GraphTypeRow(12, id("type:a." + FIM_DO_BMP), 1, id("pkg:a")),
              new GraphTypeRow(11, id("type:a." + ACIMA_DO_BMP), 1, id("pkg:a")),
              new GraphTypeRow(14, id("type:b.Late"), 13, id("pkg:b")));
      assertThat(lido.types().stream().map(GraphTypeRow::id).toList())
          .as("a ordem do id é a do CodeElementId.compareTo")
          .isSorted();
    }
  }

  @Test
  void leOsPacotesDaAnaliseEmOrdemDeId() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = primeiraAnalise(banco);
      segundaAnalise(banco);

      final GraphSource lido = GraphInputDao.read(banco, analise, () -> false);

      assertThat(lido.packages())
          .containsExactly(
              new GraphPackageRow(2, id("pkg:")),
              new GraphPackageRow(1, id("pkg:a")),
              new GraphPackageRow(13, id("pkg:b")));
    }
  }

  @Test
  void leTodaLinhaDaAnaliseComoDependencyEmOrdemDeOrigem() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = primeiraAnalise(banco);
      segundaAnalise(banco);

      final GraphSource lido = GraphInputDao.read(banco, analise, () -> false);

      assertThat(lido.rows())
          .containsExactlyInAnyOrder(
              dependencia(
                  "type:Solo", "type:a.B", DependencyType.EXTENDS, ResolutionQuality.INFERRED, 1),
              dependencia(
                  "type:a.Z", "type:a.B", DependencyType.CALLS, ResolutionQuality.RESOLVED, 3),
              dependencia(
                  "type:b.Late", "type:a.Z", DependencyType.CALLS, ResolutionQuality.RESOLVED, 2),
              dependencia(
                  "type:a.Z", "type:a.Z", DependencyType.CALLS, ResolutionQuality.RESOLVED, 2),
              dependencia(
                  "type:a.Z$Inner",
                  "type:a.Z",
                  DependencyType.FIELD_TYPE,
                  ResolutionQuality.INFERRED,
                  1),
              dependencia(
                  "type:a.B",
                  "type:java.lang.String",
                  DependencyType.PARAMETER_TYPE,
                  ResolutionQuality.RESOLVED,
                  2),
              dependencia(
                  "type:Solo",
                  "type:Missing",
                  DependencyType.CALLS,
                  ResolutionQuality.UNRESOLVED_EXTERNAL,
                  1),
              dependencia(
                  "type:a.Z", "type:a.B", DependencyType.IMPORTS, ResolutionQuality.RESOLVED, 1),
              dependencia(
                  "type:a." + ACIMA_DO_BMP,
                  "type:a." + FIM_DO_BMP,
                  DependencyType.RETURN_TYPE,
                  ResolutionQuality.RESOLVED,
                  1));

      final Map<CodeElementId, Long> chaves = new HashMap<>();
      lido.types().forEach(tipo -> chaves.put(tipo.id(), tipo.pk()));
      final List<Long> origens = new ArrayList<>();
      for (final Dependency dependencia : lido.rows()) {
        origens.add(chaves.get(dependencia.source()));
      }
      assertThat(origens).as("as linhas vêm por source_pk, a ordem que o índice dá").isSorted();
    }
  }

  @Test
  void umaAnaliseSemTipoNenhumLeTudoVazio() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      primeiraAnalise(banco);
      final long vazia = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_03"));

      final GraphSource lido = GraphInputDao.read(banco, vazia, () -> false);

      assertThat(lido.types()).isEmpty();
      assertThat(lido.packages()).isEmpty();
      assertThat(lido.rows()).isEmpty();
    }
  }

  @Test
  void umTipoInternoSemPacoteEIllegalState() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      ElementDao.insert(
          banco,
          analise,
          List.of(elemento(1, "pkg:a", null, false), elemento(2, "type:a.Orfao", null, false)));

      assertThatThrownBy(() -> GraphInputDao.read(banco, analise, () -> false))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void umaLinhaCujaPontaNaoETipoEIllegalState() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      ElementDao.insert(
          banco,
          analise,
          List.of(elemento(1, "pkg:a", null, false), elemento(2, "type:a.A", 1L, false)));
      DependencyDao.insert(
          banco,
          analise,
          List.of(linha(1, 2, 1, DependencyType.CALLS, ResolutionQuality.RESOLVED, 1, false)));

      assertThatThrownBy(() -> GraphInputDao.read(banco, analise, () -> false))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static long analiseGrande(Connection banco) throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
    final List<ElementRow> elementos = new ArrayList<>();
    elementos.add(elemento(1, "pkg:p", null, false));
    for (int i = 0; i < TIPOS_GRANDES; i++) {
      elementos.add(elemento(i + 2L, "type:p.T" + i, 1L, false));
    }
    ElementDao.insert(banco, analise, elementos);
    final List<DependencyType> tipos =
        Arrays.stream(DependencyType.values()).filter(DependencyType::isTypeEdge).toList();
    final List<DependencyRow> linhas = new ArrayList<>();
    long chave = 1;
    for (int origem = 0; origem < TIPOS_GRANDES; origem++) {
      for (int destino = 0; destino < TIPOS_GRANDES; destino++) {
        for (final DependencyType tipo : tipos) {
          linhas.add(
              linha(
                  chave++, origem + 2L, destino + 2L, tipo, ResolutionQuality.RESOLVED, 1, false));
        }
      }
    }
    assertThat(linhas).hasSizeGreaterThan(AnalysisConstants.GRAPH_READ_CHECK_STRIDE_ROWS);
    DependencyDao.insert(banco, analise, linhas);
    return analise;
  }

  @Test
  void umFornecedorCanceladoInterrompeALeituraDeMuitasLinhas() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseGrande(banco);

      assertThatThrownBy(() -> GraphInputDao.read(banco, analise, () -> true))
          .isInstanceOf(CancellationException.class);
    }
  }

  @Test
  void semCancelamentoALeituraConsultaOFornecedorELeTudo() throws Exception {
    final AtomicInteger consultas = new AtomicInteger();

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseGrande(banco);

      final GraphSource lido =
          GraphInputDao.read(
              banco,
              analise,
              () -> {
                consultas.incrementAndGet();
                return false;
              });

      final int linhas = TIPOS_GRANDES * TIPOS_GRANDES * 8;
      assertThat(lido.rows()).hasSize(linhas);
      assertThat(lido.types()).hasSize(TIPOS_GRANDES);
      assertThat(consultas)
          .as("ao menos uma consulta a cada GRAPH_READ_CHECK_STRIDE_ROWS linhas")
          .hasValueGreaterThanOrEqualTo(linhas / AnalysisConstants.GRAPH_READ_CHECK_STRIDE_ROWS);
    }
  }
}
