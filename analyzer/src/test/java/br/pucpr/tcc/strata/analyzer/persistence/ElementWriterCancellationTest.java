package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CodeMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CodeMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceRootDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceRootRow;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureCause;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ElementWriterCancellationTest {

  private static final SourceRoot MAIN =
      new SourceRoot(new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 4);

  private static final long MTIME = 1_791_000_000_000L;

  private static final int LINHAS_POR_LOTE = 1_000;

  private static final int LOTES = 3;

  private static final String BROKEN = "src/main/java/p/Broken.java";

  private static final PackageElement PACOTE_P = new PackageElement(id("pkg:p"), "p", "p");

  @TempDir private Path temp;

  private RepositoryCache cache;
  private SqliteConnections connections;

  private static CodeElementId id(String value) {
    return new CodeElementId(value);
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

  private static final class Cancelamento implements BooleanSupplier {

    private final int naConsulta;
    private boolean armado;
    private int consultas;
    private boolean virado;

    private Cancelamento(int naConsulta) {
      this.naConsulta = naConsulta;
    }

    private void armar() {
      armado = true;
    }

    @Override
    public boolean getAsBoolean() {
      if (armado && !virado && ++consultas >= naConsulta) {
        virado = true;
      }
      return virado;
    }
  }

  private static String caminho(int indice) {
    return "src/main/java/p/T" + indice + ".java";
  }

  private static List<JavaSource> fontes() {
    final List<JavaSource> fontes = new ArrayList<>();
    IntStream.range(0, LOTES)
        .forEach(indice -> fontes.add(new JavaSource(caminho(indice), 100 + indice, MTIME)));
    fontes.add(new JavaSource(BROKEN, 7, MTIME));
    return fontes;
  }

  private static SourceFile analisado(String caminho, String tipo) {
    return new SourceFile(
        caminho,
        MAIN.id(),
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations("p", List.of(tipo)),
        3);
  }

  private static List<SourceFile> preParse() {
    final List<SourceFile> arquivos = new ArrayList<>();
    IntStream.range(0, LOTES)
        .forEach(indice -> arquivos.add(analisado(caminho(indice), "T" + indice)));
    arquivos.add(analisado(BROKEN, "Broken"));
    return arquivos;
  }

  private static List<SourceFile> finais() {
    final List<SourceFile> arquivos = new ArrayList<>(preParse().subList(0, LOTES));
    arquivos.add(
        new SourceFile(
            BROKEN,
            MAIN.id(),
            SourceFileStatus.PARSE_FAILED,
            ParseFailureCause.EXTRACTION_CRASH,
            new SourceFile.Declarations("p", List.of("Broken")),
            3));
    return arquivos;
  }

  private static List<FileElement> elementosFile() {
    return preParse().stream()
        .map(
            arquivo ->
                new FileElement(
                    id("file:" + arquivo.relativePath()),
                    arquivo.relativePath().substring(arquivo.relativePath().lastIndexOf('/') + 1),
                    arquivo.relativePath(),
                    arquivo.relativePath(),
                    MAIN.id(),
                    id("pkg:p"),
                    false))
        .toList();
  }

  private static BatchElements lote(int indice) {
    final boolean primeiro = indice == 0;
    final TypeElement tipo =
        new TypeElement(
            id("type:p.T" + indice),
            "T" + indice,
            "p.T" + indice,
            TypeKind.CLASS,
            id("pkg:p"),
            caminho(indice),
            MAIN.id(),
            Set.of(),
            false,
            new SourceLines(1, 2));
    final List<MethodElement> metodos =
        IntStream.range(0, LINHAS_POR_LOTE - (primeiro ? 2 : 1))
            .mapToObj(
                m ->
                    new MethodElement(
                        id("method:p.T" + indice + "#m" + m + "()"),
                        "m" + m,
                        "p.T" + indice + "#m" + m,
                        tipo.id(),
                        caminho(indice),
                        MAIN.id(),
                        Set.of(),
                        false,
                        SourceLines.UNAVAILABLE))
            .toList();
    return new BatchElements(primeiro ? List.of(PACOTE_P) : List.of(), List.of(tipo), metodos);
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

  private String lido(String sql, Object... valores) throws SQLException {
    return connections.read(c -> DaoTestDatabase.scalar(c, sql, valores));
  }

  private long contarElementos(long analise) throws SQLException {
    return Long.parseLong(lido("SELECT count(*) FROM code_element WHERE analysis_pk = ?", analise));
  }

  private long contarFile(long analise) throws SQLException {
    return Long.parseLong(
        lido("SELECT count(*) FROM code_element WHERE analysis_pk = ? AND kind = 'FILE'", analise));
  }

  private List<SourceFileRow> arquivos(long analise) throws SQLException {
    return connections.read(c -> SourceFileDao.list(c, analise));
  }

  private List<String> violacoesDeChaveEstrangeira() throws SQLException {
    return connections.read(c -> TestCaches.column(c, "PRAGMA foreign_key_check"));
  }

  private String escritorLivre() throws SQLException {
    return connections.write(c -> "livre");
  }

  @Test
  void umCancelamentoNoMeioDeUmaDescargaSobeComoCancelamentoEDeixaSoAsDescargasInteiras()
      throws Exception {
    final long analise = novaAnalise("an_01");
    final Cancelamento cancelamento = new Cancelamento(2);
    final ElementWriter writer =
        new ElementWriter(connections, analise, List.of(MAIN), fontes(), 1, cancelamento);
    writer.begin(preParse());
    writer.accept(lote(0));
    assertThat(contarElementos(analise)).isEqualTo(LINHAS_POR_LOTE);

    cancelamento.armar();

    final Throwable falha = catchThrowable(() -> writer.accept(lote(1)));

    assertThat(falha)
        .as(
            "um cancelamento durante uma gravação sai como CancellationException, e"
                + " nunca WriteFailedException")
        .isInstanceOf(CancellationException.class)
        .isNotInstanceOf(ElementWriter.WriteFailedException.class);

    assertThat(contarElementos(analise))
        .as("só a descarga inteira confirmada antes do cancelamento")
        .isEqualTo(LINHAS_POR_LOTE);
    assertThat(lido("SELECT count(*) FROM code_element WHERE element_id LIKE ?", "%p.T1%"))
        .isEqualTo("0");
    assertThat(writer.pkOf(id("type:p.T1"))).isEmpty();
    assertThat(writer.pkOf(id("type:p.T0"))).isPresent();
    assertThat(arquivos(analise)).hasSize(LOTES + 1);
    assertThat(escritorLivre()).as("o escritor ficou livre").isEqualTo("livre");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  @Test
  void umCancelamentoDuranteOEndDesfazOEndInteiroENaoDeixaLinhaFile() throws Exception {
    final long analise = novaAnalise("an_01");
    final Cancelamento cancelamento = new Cancelamento(2);
    final ElementWriter writer =
        new ElementWriter(
            connections, analise, List.of(MAIN), fontes(), LINHAS_POR_LOTE + 1, cancelamento);
    writer.begin(preParse());
    writer.accept(lote(0));
    writer.accept(lote(1));
    writer.accept(lote(2));
    assertThat(contarElementos(analise)).isEqualTo(2L * LINHAS_POR_LOTE);
    cancelamento.armar();

    final Throwable falha = catchThrowable(() -> writer.end(finais(), elementosFile()));

    assertThat(falha)
        .as("o end() também é cancelável, pedaço a pedaço")
        .isInstanceOf(CancellationException.class)
        .isNotInstanceOf(ElementWriter.WriteFailedException.class);

    assertThat(contarFile(analise)).as("nenhuma linha FILE").isZero();
    assertThat(contarElementos(analise))
        .as("a última descarga, dentro do end, foi desfeita com ele")
        .isEqualTo(2L * LINHAS_POR_LOTE);
    assertThat(lido("SELECT count(*) FROM code_element WHERE element_id LIKE ?", "%p.T2%"))
        .isEqualTo("0");
    assertThat(arquivos(analise))
        .as("a troca de desfecho do end também foi desfeita")
        .extracting(SourceFileRow::status)
        .containsOnly(SourceFileStatus.ANALYZED);
    assertThat(
            lido(
                "SELECT count(*) FROM code_metric m JOIN code_element e ON e.id = m.element_pk"
                    + " WHERE e.analysis_pk = ? AND e.kind = 'FILE'",
                analise))
        .isEqualTo("0");
    assertThat(escritorLivre()).as("o escritor ficou livre").isEqualTo("livre");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  private record Elemento(
      String elementId,
      ElementKind kind,
      String pai,
      String simpleName,
      String qualifiedName,
      String sortKey,
      TypeKind typeKind,
      String modifiers,
      boolean isExternal,
      boolean isTest,
      String raiz,
      String arquivo,
      Integer startLine,
      Integer endLine) {}

  private record Metrica(
      String elementId, int loc, Integer unitCount, Integer ccSum, Integer ccMax, Integer ccP90) {}

  private record Gravado(
      List<Elemento> elementos,
      List<Metrica> metricas,
      List<String> raizes,
      List<String> arquivos) {}

  private Gravado gravado(long analise) throws SQLException {
    final List<ElementRow> elementos = connections.read(c -> ElementDao.list(c, analise));
    final List<SourceRootRow> raizes = connections.read(c -> SourceRootDao.list(c, analise));
    final List<SourceFileRow> arquivos = arquivos(analise);
    final List<CodeMetricRow> metricas = connections.read(c -> CodeMetricDao.list(c, analise));
    final Map<Long, String> elementoPorChave = new HashMap<>();
    elementos.forEach(linha -> elementoPorChave.put(linha.pk(), linha.elementId().value()));
    final Map<Long, String> raizPorChave = new HashMap<>();
    raizes.forEach(linha -> raizPorChave.put(linha.pk(), linha.relativePath()));
    final Map<Long, String> arquivoPorChave = new HashMap<>();
    arquivos.forEach(linha -> arquivoPorChave.put(linha.pk(), linha.relativePath()));
    return new Gravado(
        elementos.stream()
            .map(
                linha ->
                    new Elemento(
                        linha.elementId().value(),
                        linha.kind(),
                        linha.parentPk() == null ? null : elementoPorChave.get(linha.parentPk()),
                        linha.simpleName(),
                        linha.qualifiedName(),
                        linha.sortKey(),
                        linha.typeKind(),
                        linha.modifiers(),
                        linha.isExternal(),
                        linha.isTest(),
                        linha.sourceRootPk() == null
                            ? null
                            : raizPorChave.get(linha.sourceRootPk()),
                        linha.filePk() == null ? null : arquivoPorChave.get(linha.filePk()),
                        linha.startLine(),
                        linha.endLine()))
            .toList(),
        metricas.stream()
            .map(
                linha ->
                    new Metrica(
                        elementoPorChave.get(linha.elementPk()),
                        linha.loc(),
                        linha.unitCount(),
                        linha.ccSum(),
                        linha.ccMax(),
                        linha.ccP90()))
            .toList(),
        raizes.stream()
            .map(
                linha ->
                    linha.sourceRootId().value() + " " + linha.relativePath() + " " + linha.kind())
            .toList(),
        arquivos.stream()
            .map(
                linha ->
                    linha.relativePath()
                        + " "
                        + linha.sizeBytes()
                        + " "
                        + linha.mtimeMillis()
                        + " "
                        + linha.status())
            .toList());
  }

  private static void ciclo(ElementWriter writer) {
    writer.begin(preParse());
    IntStream.range(0, LOTES).forEach(indice -> writer.accept(lote(indice)));
    writer.end(finais(), elementosFile());
  }

  @Test
  void semCancelamentoOGravadorCancelavelGravaAsMesmasLinhasQueODeQuatroArgumentos()
      throws Exception {
    final long deQuatro = novaAnalise("an_01");
    ciclo(new ElementWriter(connections, deQuatro, List.of(MAIN), fontes()));
    final long cancelavel = novaAnalise("an_02");

    ciclo(new ElementWriter(connections, cancelavel, List.of(MAIN), fontes(), () -> false));

    final Gravado esperado = gravado(deQuatro);
    assertThat(esperado.elementos()).hasSize(LOTES * LINHAS_POR_LOTE + LOTES + 1);
    assertThat(esperado.metricas()).isNotEmpty();
    assertThat(gravado(cancelavel)).isEqualTo(esperado);
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }
}
