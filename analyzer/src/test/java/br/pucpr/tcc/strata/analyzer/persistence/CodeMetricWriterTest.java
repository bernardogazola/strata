package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchDependencies;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchStructure;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureCause;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Complexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class CodeMetricWriterTest {

  private static final SourceRoot MAIN =
      new SourceRoot(new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 10);

  private static final String ORDER = "src/main/java/a/Order.java";
  private static final String OTHER = "src/main/java/a/Other.java";
  private static final String INFO = "src/main/java/a/package-info.java";
  private static final String BROKEN = "src/main/java/a/Broken.java";
  private static final String HUGE = "src/main/java/a/Huge.java";
  private static final String EMPTY = "src/main/java/a/Empty.java";
  private static final String REPORT = "src/main/java/b/Report.java";
  private static final String MODULE_INFO = "src/main/java/module-info.java";

  private static final int ACENTO_AGUDO_COMBINANTE = 0x0301;

  private static final String CAFE_NFD =
      "src/main/java/a/Cafe" + Character.toString(ACENTO_AGUDO_COMBINANTE) + ".java";

  private static final String CAFE = Normalizer.normalize(CAFE_NFD, Normalizer.Form.NFC);

  private static final List<String> CAMINHOS =
      List.of(ORDER, OTHER, INFO, BROKEN, HUGE, EMPTY, REPORT, MODULE_INFO, CAFE, CAFE_NFD);

  private static final List<JavaSource> FONTES =
      CAMINHOS.stream().map(caminho -> new JavaSource(caminho, 100, 1_791_000_000_000L)).toList();

  private static final List<SourceFile> FINAIS =
      List.of(
          analisado(ORDER, "a", 40, "Order"),
          analisado(OTHER, "a", 11, "Other"),
          analisado(INFO, "a", 2),
          new SourceFile(
              BROKEN,
              MAIN.id(),
              SourceFileStatus.PARSE_FAILED,
              ParseFailureCause.EXTRACTION_CRASH,
              new SourceFile.Declarations("a", List.of("Broken")),
              7),
          new SourceFile(HUGE, MAIN.id(), SourceFileStatus.SKIPPED_TOO_LARGE, null, null),
          analisado(EMPTY, "a", 0),
          analisado(REPORT, "b", 20, "Report"),
          analisado(MODULE_INFO, "", 4),
          analisado(CAFE, "a", 5, "Cafe"),
          analisado(CAFE_NFD, "a", 6, "Cafe"));

  private static final List<SourceFile> PRE_PARSE =
      FINAIS.stream()
          .map(
              arquivo ->
                  new SourceFile(
                      arquivo.relativePath(),
                      arquivo.rootId(),
                      arquivo.status() == SourceFileStatus.SKIPPED_TOO_LARGE
                          ? SourceFileStatus.SKIPPED_TOO_LARGE
                          : SourceFileStatus.ANALYZED,
                      null,
                      arquivo.declarations()))
          .toList();

  private static final List<FileElement> ARQUIVOS =
      List.of(
          arquivo(ORDER, "pkg:a"),
          arquivo(OTHER, "pkg:a"),
          arquivo(INFO, "pkg:a"),
          arquivo(BROKEN, "pkg:a"),
          arquivo(HUGE, "pkg:a"),
          arquivo(EMPTY, "pkg:a"),
          arquivo(REPORT, "pkg:b"),
          arquivo(MODULE_INFO, null),
          arquivo(CAFE, "pkg:a"));

  private static final PackageElement PACOTE_A = new PackageElement(id("pkg:a"), "a", "a");
  private static final PackageElement PACOTE_B = new PackageElement(id("pkg:b"), "b", "b");

  private static final TypeElement TIPO_ORDER = tipo("a.Order", "Order", ORDER, 3, 40);
  private static final TypeElement TIPO_LINE = tipo("a.Order$Line", "Line", ORDER, 10, 12);
  private static final TypeElement TIPO_OTHER = tipo("a.Other", "Other", OTHER, 1, 11);
  private static final TypeElement TIPO_REPORT = tipo("b.Report", "Report", REPORT, 1, 30);

  private static final MethodElement TOTAL = metodo(TIPO_ORDER, "total", new SourceLines(20, 25));
  private static final MethodElement CONSTRUTOR =
      metodo(TIPO_ORDER, "<init>", SourceLines.UNAVAILABLE);
  private static final MethodElement AMOUNT = metodo(TIPO_LINE, "amount", new SourceLines(11, 11));
  private static final MethodElement RUN = metodo(TIPO_OTHER, "run", new SourceLines(4, 8));
  private static final MethodElement RENDER = metodo(TIPO_REPORT, "render", new SourceLines(5, 20));

  private static final BatchStructure LOTE_ORDER =
      new BatchStructure(
          new BatchElements(
              List.of(PACOTE_A),
              List.of(TIPO_ORDER, TIPO_LINE),
              List.of(TOTAL, CONSTRUTOR, AMOUNT)),
          BatchDependencies.NONE,
          List.of(
              new CodeMetric(TIPO_ORDER.id(), 30, Complexity.of(List.of(1, 3, 2))),
              new CodeMetric(TIPO_LINE.id(), 3, Complexity.NONE),
              new CodeMetric(TOTAL.id(), 5, Complexity.of(List.of(3, 2))),
              new CodeMetric(CONSTRUTOR.id(), 0, null),
              new CodeMetric(AMOUNT.id(), 1, null)));

  private static final BatchStructure LOTE_OTHER =
      new BatchStructure(
          new BatchElements(List.of(), List.of(TIPO_OTHER), List.of(RUN)),
          BatchDependencies.NONE,
          List.of(
              new CodeMetric(RUN.id(), 4, Complexity.of(List.of(1))),
              new CodeMetric(TIPO_OTHER.id(), 9, Complexity.of(List.of(1)))));

  private static final BatchStructure LOTE_REPORT =
      new BatchStructure(
          new BatchElements(List.of(PACOTE_B), List.of(TIPO_REPORT), List.of(RENDER)),
          BatchDependencies.NONE,
          List.of(
              new CodeMetric(TIPO_REPORT.id(), 12, Complexity.of(List.of(2, 4))),
              new CodeMetric(RENDER.id(), 10, Complexity.of(List.of(4)))));

  private static final List<String> TIPOS_E_METODOS =
      List.of(
          "method:a.Order#<init>()|0|0|NULL|NULL|NULL",
          "method:a.Order#total()|5|2|5|3|NULL",
          "method:a.Order$Line#amount()|1|0|NULL|NULL|NULL",
          "method:a.Other#run()|4|1|1|1|NULL",
          "method:b.Report#render()|10|1|4|4|NULL",
          "type:a.Order|30|3|6|3|3",
          "type:a.Order$Line|3|0|0|0|0",
          "type:a.Other|9|1|1|1|1",
          "type:b.Report|12|2|6|4|4");

  private static final List<String> TUDO =
      List.of(
          "file:" + BROKEN + "|7|NULL|NULL|NULL|NULL",
          "file:" + CAFE + "|5|NULL|NULL|NULL|NULL",
          "file:" + EMPTY + "|0|NULL|NULL|NULL|NULL",
          "file:" + ORDER + "|40|NULL|NULL|NULL|NULL",
          "file:" + OTHER + "|11|NULL|NULL|NULL|NULL",
          "file:" + INFO + "|2|NULL|NULL|NULL|NULL",
          "file:" + REPORT + "|20|NULL|NULL|NULL|NULL",
          "file:" + MODULE_INFO + "|4|NULL|NULL|NULL|NULL",
          TIPOS_E_METODOS.get(0),
          TIPOS_E_METODOS.get(1),
          TIPOS_E_METODOS.get(2),
          TIPOS_E_METODOS.get(3),
          TIPOS_E_METODOS.get(4),
          "pkg:a|65|4|7|3|NULL",
          "pkg:b|20|2|6|4|NULL",
          TIPOS_E_METODOS.get(5),
          TIPOS_E_METODOS.get(6),
          TIPOS_E_METODOS.get(7),
          TIPOS_E_METODOS.get(8));

  private static final String METRICAS =
      """
      SELECT e.element_id || '|' || m.loc || '|' || coalesce(m.unit_count, 'NULL')
             || '|' || coalesce(m.cc_sum, 'NULL') || '|' || coalesce(m.cc_max, 'NULL')
             || '|' || coalesce(m.cc_p90, 'NULL')
        FROM code_metric m
        JOIN code_element e ON e.id = m.element_pk
       ORDER BY e.element_id""";

  @TempDir private Path temp;

  private RepositoryCache cache;
  private SqliteConnections connections;

  private static CodeElementId id(String value) {
    return new CodeElementId(value);
  }

  private static SourceFile analisado(String caminho, String pacote, int loc, String... tipos) {
    return new SourceFile(
        caminho,
        MAIN.id(),
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations(pacote, List.of(tipos)),
        loc);
  }

  private static FileElement arquivo(String caminho, String pacote) {
    return new FileElement(
        CodeElementId.ofFile(caminho),
        caminho.substring(caminho.lastIndexOf('/') + 1),
        caminho,
        caminho,
        MAIN.id(),
        pacote == null ? null : id(pacote),
        false);
  }

  private static TypeElement tipo(
      String qualificado, String nome, String caminho, int inicio, int fim) {
    return new TypeElement(
        id("type:" + qualificado),
        nome,
        qualificado.replace('$', '.'),
        TypeKind.CLASS,
        id("pkg:" + qualificado.substring(0, qualificado.indexOf('.'))),
        caminho,
        MAIN.id(),
        Set.of(),
        false,
        new SourceLines(inicio, fim));
  }

  private static MethodElement metodo(TypeElement dono, String nome, SourceLines linhas) {
    return new MethodElement(
        id("method:" + dono.id().sortKey() + "#" + nome + "()"),
        nome,
        dono.qualifiedName() + "#" + nome,
        dono.id(),
        dono.filePath(),
        MAIN.id(),
        Set.of(),
        false,
        linhas);
  }

  @BeforeEach
  void abrir() throws Exception {
    final Path real = temp.toRealPath();
    final Path repositorio = Files.createDirectories(real.resolve("repo"));
    cache =
        RepositoryCache.open(
                PersistenceRoot.prepare(real.resolve("data")),
                TestCaches.identityV01(),
                repositorio,
                "0.0.0-test",
                TestCaches.steppingClock())
            .cache();
    connections = cache.connections();
  }

  @AfterEach
  void fechar() {
    cache.close();
  }

  private long novaAnalise(String analysisId) throws SQLException {
    final NewAnalysis analise =
        new NewAnalysis(
            analysisId,
            1_792_000_000_000L,
            null,
            "0.0.0-test",
            1,
            1,
            "c".repeat(64),
            "{}",
            null,
            true,
            HistoryScope.ALL,
            null);
    return connections.write(c -> AnalysisDao.insertRunning(c, analise));
  }

  private ElementWriter gravador(long analise, int flushRows) {
    final ElementWriter writer =
        new ElementWriter(connections, analise, List.of(MAIN), FONTES, flushRows);
    writer.begin(PRE_PARSE);
    return writer;
  }

  private List<String> metricas() throws SQLException {
    return connections.read(c -> TestCaches.column(c, METRICAS));
  }

  private String contar(String tabela) throws SQLException {
    return connections.read(c -> TestCaches.scalar(c, "SELECT count(*) FROM " + tabela));
  }

  private String lido(String sql, Object... valores) throws SQLException {
    return connections.read(c -> DaoTestDatabase.scalar(c, sql, valores));
  }

  @Test
  void oCicloInteiroGravaUmaLinhaPorElementoInternoComOPadraoDeNulosDeCadaTipo() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), Integer.MAX_VALUE);
    writer.accept(LOTE_ORDER);
    writer.accept(LOTE_OTHER);
    writer.accept(LOTE_REPORT);

    writer.end(FINAIS, ARQUIVOS);

    assertThat(metricas()).containsExactlyElementsOf(TUDO);
    assertThat(
            lido(
                "SELECT count(*) FROM code_metric m JOIN code_element e ON e.id = m.element_pk"
                    + " WHERE m.analysis_pk = e.analysis_pk"))
        .isEqualTo(String.valueOf(TUDO.size()));
    final List<String> violacoes =
        connections.read(c -> TestCaches.column(c, "PRAGMA foreign_key_check"));
    assertThat(violacoes).isEmpty();
  }

  @Test
  void asMetricasDeUmaDescargaEntramNaTransacaoDosElementosEUmaRecusaDesfazOsDois()
      throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(LOTE_ORDER);
    assertThat(metricas())
        .as("a primeira descarga confirma as métricas com os elementos, já com as chaves deles")
        .containsExactlyElementsOf(
            TIPOS_E_METODOS.stream().filter(linha -> linha.contains("a.Order")).toList());
    connections.write(
        c ->
            DaoTestDatabase.execute(
                c,
                "CREATE TEMP TRIGGER recusa BEFORE INSERT ON code_metric"
                    + " BEGIN SELECT RAISE(ABORT, 'probe'); END"));

    assertThatThrownBy(() -> writer.accept(LOTE_REPORT))
        .isInstanceOf(ElementWriter.WriteFailedException.class)
        .satisfies(
            failure ->
                assertThat(DaoTestDatabase.resultCode(failure.getCause()))
                    .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER));

    assertThat(lido("SELECT count(*) FROM code_element WHERE element_id LIKE ?", "%b.Report%"))
        .as("os elementos da descarga recusada saíram com a métrica dela")
        .isEqualTo("0");
    assertThat(writer.pkOf(TIPO_REPORT.id())).isEmpty();
    assertThat(contar("code_element")).isEqualTo("6");
    assertThat(contar("code_metric")).isEqualTo("5");
  }

  @Test
  void umaMetricaDeUmElementoQueADescargaNaoNumerouFalhaENadaDelaFica() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    final List<CodeMetric> comFantasma = new ArrayList<>(LOTE_ORDER.metrics());
    comFantasma.add(new CodeMetric(id("method:a.Order#ghost()"), 2, Complexity.of(List.of(1))));

    assertThatThrownBy(
            () ->
                writer.accept(
                    new BatchStructure(LOTE_ORDER.elements(), BatchDependencies.NONE, comFantasma)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("method:a.Order#ghost()");

    assertThat(contar("code_element")).isEqualTo("0");
    assertThat(contar("code_metric")).isEqualTo("0");
    assertThat(writer.pkOf(TIPO_ORDER.id())).isEmpty();
  }

  @Test
  void umaMetricaDeUmTipoDeOutraDescargaFalhaMesmoComOTipoNoIndice() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(LOTE_ORDER);
    assertThat(writer.pkOf(TIPO_ORDER.id())).isEqualTo(OptionalLong.of(2));
    final List<CodeMetric> comAntigo = new ArrayList<>(LOTE_OTHER.metrics());
    comAntigo.add(new CodeMetric(TIPO_ORDER.id(), 30, Complexity.of(List.of(1))));

    assertThatThrownBy(
            () ->
                writer.accept(
                    new BatchStructure(LOTE_OTHER.elements(), BatchDependencies.NONE, comAntigo)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("type:a.Order");

    assertThat(contar("code_element")).isEqualTo("6");
    assertThat(contar("code_metric")).isEqualTo("5");
    assertThat(writer.pkOf(TIPO_OTHER.id())).isEmpty();
  }

  @Test
  void oEndGravaUmaLinhaFileSoParaOsArquivosGravadosQueTemLoc() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), Integer.MAX_VALUE);
    writer.accept(LOTE_ORDER);
    writer.accept(LOTE_OTHER);
    writer.accept(LOTE_REPORT);

    writer.end(FINAIS, ARQUIVOS);

    assertThat(lido("SELECT count(*) FROM code_element WHERE kind = ?", "FILE")).isEqualTo("9");
    assertThat(metricas())
        .filteredOn(linha -> linha.startsWith("file:"))
        .as("nove FILE, oito linhas: o SKIPPED_TOO_LARGE sem loc fica fora")
        .hasSize(8)
        .noneMatch(linha -> linha.startsWith("file:" + HUGE + "|"))
        .as("o perdedor da repetição tem loc e não tem elemento: nenhuma linha, nem a 6 dele")
        .noneMatch(linha -> linha.endsWith("|6|NULL|NULL|NULL|NULL"));
    assertThat(
            lido(
                "SELECT m.loc FROM code_metric m JOIN code_element e ON e.id = m.element_pk"
                    + " WHERE e.element_id = ? AND e.parent_pk IS NULL",
                "file:" + MODULE_INFO))
        .as("o module-info.java tem linha e não tem pacote")
        .isEqualTo("4");
  }

  @Test
  void aSomaDosPacotesSaiIgualQuandoArquivosETiposDoPacoteCruzamDescargas() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(LOTE_ORDER);
    writer.accept(LOTE_OTHER);
    writer.accept(LOTE_REPORT);
    assertThat(contar("code_metric")).isEqualTo(String.valueOf(TIPOS_E_METODOS.size()));

    writer.end(FINAIS, ARQUIVOS);

    assertThat(metricas()).containsExactlyElementsOf(TUDO);
  }

  @Test
  void asLinhasDeMetricaSozinhasCortamADescarga() throws Exception {
    final BatchElements elementos =
        new BatchElements(List.of(PACOTE_B), List.of(TIPO_REPORT), List.of(RENDER));
    final int limiar = LOTE_REPORT.size();
    assertThat(new BatchStructure(elementos, BatchDependencies.NONE).size())
        .as("sem as duas métricas, o mesmo lote fica abaixo do limiar")
        .isEqualTo(limiar - 2);
    final ElementWriter semMetricas = gravador(novaAnalise("an_01"), limiar);
    semMetricas.accept(new BatchStructure(elementos, BatchDependencies.NONE));
    assertThat(contar("code_element")).isEqualTo("0");

    final ElementWriter comMetricas = gravador(novaAnalise("an_02"), limiar);
    comMetricas.accept(LOTE_REPORT);

    assertThat(contar("code_element")).isEqualTo("3");
    assertThat(metricas()).containsExactly(TIPOS_E_METODOS.get(4), TIPOS_E_METODOS.get(8));
  }

  @Test
  void umaAnaliseCanceladaAntesDoEndTemSoAsLinhasDeTypeEMethod() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);
    writer.accept(LOTE_ORDER);
    writer.accept(LOTE_OTHER);
    writer.accept(LOTE_REPORT);

    assertThat(metricas()).containsExactlyElementsOf(TIPOS_E_METODOS);
    assertThat(lido("SELECT status FROM analysis WHERE id = ?", analise)).isEqualTo("RUNNING");
  }
}
