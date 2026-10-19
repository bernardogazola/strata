package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
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
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Modifier;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class ElementWriterTest {

  private static final SourceRoot MAIN =
      new SourceRoot(new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 3);
  private static final SourceRoot TESTE =
      new SourceRoot(new SourceRootId("sr_b1c4d7e2"), "src/test/java", SourceRootKind.TEST, 2);
  private static final List<SourceRoot> RAIZES = List.of(MAIN, TESTE);

  private static final String ORDER = "src/main/java/a/Order.java";
  private static final String BROKEN = "src/main/java/a/Broken.java";
  private static final String HUGE = "src/main/java/a/Huge.java";
  private static final String ORDER_TEST = "src/test/java/a/OrderTest.java";
  private static final String ONLY_TEST = "src/test/java/b/OnlyTest.java";

  private static final long MTIME = 1_791_000_000_000L;

  private static final List<JavaSource> FONTES =
      List.of(
          new JavaSource(ORDER, 1_200, MTIME + 1),
          new JavaSource(BROKEN, 300, MTIME + 2),
          new JavaSource(HUGE, 3_000_000, MTIME + 3),
          new JavaSource(ORDER_TEST, 800, MTIME + 4),
          new JavaSource(ONLY_TEST, 90, MTIME + 5),
          new JavaSource("outro/src/main/java/Fora.java", 5, MTIME + 6));

  private static final List<SourceFile> PRE_PARSE =
      List.of(
          analisado(ORDER, MAIN, "a", "Order"),
          analisado(BROKEN, MAIN, "a", "Broken"),
          new SourceFile(HUGE, MAIN.id(), SourceFileStatus.SKIPPED_TOO_LARGE, null, null),
          analisado(ORDER_TEST, TESTE, "a", "OrderTest"),
          analisado(ONLY_TEST, TESTE, "b", "OnlyTest"));

  private static final List<SourceFile> FINAIS =
      List.of(
          PRE_PARSE.get(0),
          new SourceFile(
              BROKEN,
              MAIN.id(),
              SourceFileStatus.PARSE_FAILED,
              ParseFailureCause.EXTRACTION_CRASH,
              new SourceFile.Declarations("a", List.of("Broken"))),
          PRE_PARSE.get(2),
          PRE_PARSE.get(3),
          PRE_PARSE.get(4));

  private static final BatchElements LOTE_MAIN =
      new BatchElements(
          List.of(new PackageElement(id("pkg:a"), "a", "a")),
          List.of(
              new TypeElement(
                  id("type:a.Order"),
                  "Order",
                  "a.Order",
                  TypeKind.CLASS,
                  id("pkg:a"),
                  ORDER,
                  MAIN.id(),
                  Set.of(Modifier.FINAL, Modifier.PUBLIC),
                  false,
                  new SourceLines(3, 40)),
              new TypeElement(
                  id("type:a.Order$Line"),
                  "Line",
                  "a.Order.Line",
                  TypeKind.RECORD,
                  id("pkg:a"),
                  ORDER,
                  MAIN.id(),
                  Set.of(Modifier.STATIC, Modifier.PUBLIC),
                  false,
                  new SourceLines(10, 12))),
          List.of(
              new MethodElement(
                  id("method:a.Order#<init>()"),
                  "<init>",
                  "a.Order#<init>",
                  id("type:a.Order"),
                  ORDER,
                  MAIN.id(),
                  Set.of(),
                  false,
                  SourceLines.UNAVAILABLE),
              new MethodElement(
                  id("method:a.Order#total(int,java.lang.String)"),
                  "total",
                  "a.Order#total",
                  id("type:a.Order"),
                  ORDER,
                  MAIN.id(),
                  Set.of(Modifier.SYNCHRONIZED, Modifier.FINAL, Modifier.PRIVATE, Modifier.STATIC),
                  false,
                  new SourceLines(20, 25)),
              new MethodElement(
                  id("method:a.Order$Line#amount()"),
                  "amount",
                  "a.Order.Line#amount",
                  id("type:a.Order$Line"),
                  ORDER,
                  MAIN.id(),
                  Set.of(Modifier.PUBLIC),
                  false,
                  SourceLines.UNAVAILABLE)));

  private static final BatchElements LOTE_TESTE =
      new BatchElements(
          List.of(new PackageElement(id("pkg:b"), "b", "b")),
          List.of(
              new TypeElement(
                  id("type:a.OrderTest"),
                  "OrderTest",
                  "a.OrderTest",
                  TypeKind.CLASS,
                  id("pkg:a"),
                  ORDER_TEST,
                  TESTE.id(),
                  Set.of(),
                  true,
                  new SourceLines(5, 30)),
              new TypeElement(
                  id("type:b.OnlyTest"),
                  "OnlyTest",
                  "b.OnlyTest",
                  TypeKind.INTERFACE,
                  id("pkg:b"),
                  ONLY_TEST,
                  TESTE.id(),
                  Set.of(Modifier.NON_SEALED, Modifier.PUBLIC),
                  true,
                  new SourceLines(1, 9))),
          List.of());

  private static final List<FileElement> ARQUIVOS =
      List.of(
          arquivo(ORDER, MAIN, "pkg:a"),
          arquivo(BROKEN, MAIN, "pkg:a"),
          arquivo(HUGE, MAIN, null),
          arquivo(ORDER_TEST, TESTE, "pkg:a"),
          arquivo(ONLY_TEST, TESTE, "pkg:b"));

  private static final List<ElementRow> ELEMENTOS =
      List.of(
          new ElementRow(
              1,
              id("pkg:a"),
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
              null),
          new ElementRow(
              2,
              id("type:a.Order"),
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
              40),
          new ElementRow(
              3,
              id("type:a.Order$Line"),
              ElementKind.TYPE,
              1L,
              "Line",
              "a.Order.Line",
              "a.Order$Line",
              TypeKind.RECORD,
              "public static",
              false,
              false,
              1L,
              1L,
              10,
              12),
          new ElementRow(
              4,
              id("method:a.Order#<init>()"),
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
              null),
          new ElementRow(
              5,
              id("method:a.Order#total(int,java.lang.String)"),
              ElementKind.METHOD,
              2L,
              "total",
              "a.Order#total",
              "a.Order#total(int,java.lang.String)",
              null,
              "private static final synchronized",
              false,
              false,
              1L,
              1L,
              20,
              25),
          new ElementRow(
              6,
              id("method:a.Order$Line#amount()"),
              ElementKind.METHOD,
              3L,
              "amount",
              "a.Order.Line#amount",
              "a.Order$Line#amount()",
              null,
              "public",
              false,
              false,
              1L,
              1L,
              null,
              null),
          new ElementRow(
              7,
              id("pkg:b"),
              ElementKind.PACKAGE,
              null,
              "b",
              "b",
              "b",
              null,
              "",
              false,
              true,
              null,
              null,
              null,
              null),
          new ElementRow(
              8,
              id("type:a.OrderTest"),
              ElementKind.TYPE,
              1L,
              "OrderTest",
              "a.OrderTest",
              "a.OrderTest",
              TypeKind.CLASS,
              "",
              false,
              true,
              2L,
              4L,
              5,
              30),
          new ElementRow(
              9,
              id("type:b.OnlyTest"),
              ElementKind.TYPE,
              7L,
              "OnlyTest",
              "b.OnlyTest",
              "b.OnlyTest",
              TypeKind.INTERFACE,
              "public non-sealed",
              false,
              true,
              2L,
              5L,
              1,
              9),
          new ElementRow(
              10,
              id("file:" + ORDER),
              ElementKind.FILE,
              1L,
              "Order.java",
              ORDER,
              ORDER,
              null,
              "",
              false,
              false,
              1L,
              1L,
              null,
              null),
          new ElementRow(
              11,
              id("file:" + BROKEN),
              ElementKind.FILE,
              1L,
              "Broken.java",
              BROKEN,
              BROKEN,
              null,
              "",
              false,
              false,
              1L,
              2L,
              null,
              null),
          new ElementRow(
              12,
              id("file:" + HUGE),
              ElementKind.FILE,
              null,
              "Huge.java",
              HUGE,
              HUGE,
              null,
              "",
              false,
              false,
              1L,
              3L,
              null,
              null),
          new ElementRow(
              13,
              id("file:" + ORDER_TEST),
              ElementKind.FILE,
              1L,
              "OrderTest.java",
              ORDER_TEST,
              ORDER_TEST,
              null,
              "",
              false,
              true,
              2L,
              4L,
              null,
              null),
          new ElementRow(
              14,
              id("file:" + ONLY_TEST),
              ElementKind.FILE,
              7L,
              "OnlyTest.java",
              ONLY_TEST,
              ONLY_TEST,
              null,
              "",
              false,
              true,
              2L,
              5L,
              null,
              null));

  private static final List<SourceRootRow> RAIZES_GRAVADAS =
      List.of(
          new SourceRootRow(1, MAIN.id(), "src/main/java", SourceRootKind.MAIN, 3),
          new SourceRootRow(2, TESTE.id(), "src/test/java", SourceRootKind.TEST, 2));

  private static final List<SourceFileRow> ARQUIVOS_DO_BEGIN =
      List.of(
          new SourceFileRow(1, ORDER, 1_200, MTIME + 1, SourceFileStatus.ANALYZED),
          new SourceFileRow(2, BROKEN, 300, MTIME + 2, SourceFileStatus.ANALYZED),
          new SourceFileRow(3, HUGE, 3_000_000, MTIME + 3, SourceFileStatus.SKIPPED_TOO_LARGE),
          new SourceFileRow(4, ORDER_TEST, 800, MTIME + 4, SourceFileStatus.ANALYZED),
          new SourceFileRow(5, ONLY_TEST, 90, MTIME + 5, SourceFileStatus.ANALYZED));

  private static final int LINHAS_POR_LOTE = 1_000;
  private static final int LOTES_ABAIXO_DO_LIMIAR = 32;

  @TempDir private Path temp;

  private RepositoryCache cache;
  private SqliteConnections connections;

  private static CodeElementId id(String value) {
    return new CodeElementId(value);
  }

  private static SourceFile analisado(String caminho, SourceRoot raiz, String pacote, String tipo) {
    return new SourceFile(
        caminho,
        raiz.id(),
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations(pacote, List.of(tipo)));
  }

  private static FileElement arquivo(String caminho, SourceRoot raiz, String pacote) {
    return new FileElement(
        id("file:" + caminho),
        caminho.substring(caminho.lastIndexOf('/') + 1),
        caminho,
        caminho,
        raiz.id(),
        pacote == null ? null : id(pacote),
        raiz.kind() == SourceRootKind.TEST);
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

  private long contarElementos() throws SQLException {
    return Long.parseLong(lido("SELECT count(*) FROM code_element"));
  }

  private List<ElementRow> elementos(long analise) throws SQLException {
    return connections.read(c -> ElementDao.list(c, analise));
  }

  private List<SourceFileRow> arquivos(long analise) throws SQLException {
    return connections.read(c -> SourceFileDao.list(c, analise));
  }

  private List<SourceRootRow> raizes(long analise) throws SQLException {
    return connections.read(c -> SourceRootDao.list(c, analise));
  }

  private String peloEscritor(String sql) throws SQLException {
    return connections.write(c -> DaoTestDatabase.scalar(c, sql));
  }

  private String lido(String sql, Object... valores) throws SQLException {
    return connections.read(c -> DaoTestDatabase.scalar(c, sql, valores));
  }

  private List<String> violacoesDeChaveEstrangeira() throws SQLException {
    return connections.read(c -> TestCaches.column(c, "PRAGMA foreign_key_check"));
  }

  private ElementWriter cicloCompleto(long analise) {
    final ElementWriter writer = new ElementWriter(connections, analise, RAIZES, FONTES);
    writer.begin(PRE_PARSE);
    writer.accept(LOTE_MAIN);
    writer.accept(LOTE_TESTE);
    writer.end(FINAIS, ARQUIVOS);
    return writer;
  }

  @Test
  void oBeginGravaUmaRaizPorRaizEUmArquivoPorArquivoComTamanhoEDataDaVarredura() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = new ElementWriter(connections, analise, RAIZES, FONTES);

    writer.begin(PRE_PARSE);

    assertThat(raizes(analise)).isEqualTo(RAIZES_GRAVADAS);
    assertThat(arquivos(analise)).isEqualTo(ARQUIVOS_DO_BEGIN);
    assertThat(contarElementos()).isZero();
  }

  @Test
  void umArquivoSemJavaSourceEDefeitoENadaEGravado() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = new ElementWriter(connections, analise, RAIZES, FONTES);
    final List<SourceFile> comFantasma = new ArrayList<>(PRE_PARSE);
    comFantasma.add(analisado("src/main/java/a/Ghost.java", MAIN, "a", "Ghost"));

    assertThatThrownBy(() -> writer.begin(comFantasma))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("src/main/java/a/Ghost.java");
    assertThat(raizes(analise)).isEmpty();
    assertThat(arquivos(analise)).isEmpty();
  }

  @Test
  void abaixoDoLimiarOAcceptNaoGravaNadaEOEndGravaTudoNumaTransacao() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = new ElementWriter(connections, analise, RAIZES, FONTES);
    writer.begin(PRE_PARSE);

    writer.accept(LOTE_MAIN);
    writer.accept(LOTE_TESTE);

    assertThat(contarElementos()).isZero();
    assertThat(writer.pkOf(id("pkg:a"))).isEmpty();

    writer.end(FINAIS, ARQUIVOS);

    assertThat(elementos(analise)).isEqualTo(ELEMENTOS);
    assertThat(writer.pkOf(id("pkg:a"))).isEqualTo(OptionalLong.of(1));
    assertThat(writer.pkOf(id("type:a.Order$Line"))).isEqualTo(OptionalLong.of(3));
    assertThat(writer.pkOf(id("pkg:b"))).isEqualTo(OptionalLong.of(7));
    assertThat(writer.pkOf(id("method:a.Order#<init>()"))).isEmpty();
    assertThat(writer.pkOf(id("file:" + ORDER))).isEmpty();
  }

  @Test
  void oEndReescreveSoOsDesfechosQueMudaram() throws Exception {
    final long analise = novaAnalise("an_01");
    connections.write(
        c -> {
          DaoTestDatabase.execute(c, "CREATE TEMP TABLE reescritas (pk INTEGER)");
          return DaoTestDatabase.execute(
              c,
              "CREATE TEMP TRIGGER registra AFTER UPDATE OF status ON source_file"
                  + " BEGIN INSERT INTO reescritas VALUES (new.id); END");
        });

    cicloCompleto(analise);

    assertThat(peloEscritor("SELECT group_concat(pk, ',') FROM reescritas")).isEqualTo("2");
    assertThat(arquivos(analise))
        .containsExactly(
            ARQUIVOS_DO_BEGIN.get(0),
            new SourceFileRow(2, BROKEN, 300, MTIME + 2, SourceFileStatus.PARSE_FAILED),
            ARQUIVOS_DO_BEGIN.get(2),
            ARQUIVOS_DO_BEGIN.get(3),
            ARQUIVOS_DO_BEGIN.get(4));
  }

  @Test
  void umaSegundaAnaliseNumeraDepoisDaPrimeiraEAsDuasVoltamIntactas() throws Exception {
    final long primeira = novaAnalise("an_01");
    cicloCompleto(primeira);
    final long segunda = novaAnalise("an_02");

    final ElementWriter writer = cicloCompleto(segunda);

    assertThat(elementos(primeira)).isEqualTo(ELEMENTOS);
    assertThat(elementos(segunda)).isEqualTo(deslocados(ELEMENTOS, 14, 2, 5));
    assertThat(raizes(segunda))
        .containsExactly(
            new SourceRootRow(3, MAIN.id(), "src/main/java", SourceRootKind.MAIN, 3),
            new SourceRootRow(4, TESTE.id(), "src/test/java", SourceRootKind.TEST, 2));
    assertThat(arquivos(segunda).getFirst())
        .isEqualTo(new SourceFileRow(6, ORDER, 1_200, MTIME + 1, SourceFileStatus.ANALYZED));
    assertThat(writer.pkOf(id("type:a.Order"))).isEqualTo(OptionalLong.of(16));
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  private static List<ElementRow> deslocados(
      List<ElementRow> linhas, long elementos, long raizes, long arquivos) {
    return linhas.stream()
        .map(
            linha ->
                new ElementRow(
                    linha.pk() + elementos,
                    linha.elementId(),
                    linha.kind(),
                    linha.parentPk() == null ? null : linha.parentPk() + elementos,
                    linha.simpleName(),
                    linha.qualifiedName(),
                    linha.sortKey(),
                    linha.typeKind(),
                    linha.modifiers(),
                    linha.isExternal(),
                    linha.isTest(),
                    linha.sourceRootPk() == null ? null : linha.sourceRootPk() + raizes,
                    linha.filePk() == null ? null : linha.filePk() + arquivos,
                    linha.startLine(),
                    linha.endLine()))
        .toList();
  }

  private static final PackageElement PACOTE_P = new PackageElement(id("pkg:p"), "p", "p");

  private static String caminhoDoLote(int indice) {
    return "src/main/java/p/T" + indice + ".java";
  }

  private static List<JavaSource> fontesDosLotes(int quantos) {
    return IntStream.range(0, quantos)
        .mapToObj(indice -> new JavaSource(caminhoDoLote(indice), indice, MTIME))
        .toList();
  }

  private static List<SourceFile> arquivosDosLotes(int quantos) {
    return IntStream.range(0, quantos)
        .mapToObj(indice -> analisado(caminhoDoLote(indice), MAIN, "p", "T" + indice))
        .toList();
  }

  private static TypeElement tipoDoLote(int indice, String pacote) {
    return new TypeElement(
        id("type:" + pacote + ".T" + indice),
        "T" + indice,
        pacote + ".T" + indice,
        TypeKind.CLASS,
        id("pkg:" + pacote),
        caminhoDoLote(indice),
        MAIN.id(),
        Set.of(),
        false,
        new SourceLines(1, 2));
  }

  private static List<MethodElement> metodos(TypeElement tipo, int quantos) {
    return IntStream.range(0, quantos)
        .mapToObj(
            m ->
                new MethodElement(
                    id("method:" + tipo.id().sortKey() + "#m" + m + "()"),
                    "m" + m,
                    tipo.qualifiedName() + "#m" + m,
                    tipo.id(),
                    tipo.filePath(),
                    MAIN.id(),
                    Set.of(),
                    false,
                    SourceLines.UNAVAILABLE))
        .toList();
  }

  private static BatchElements loteDeMil(int indice) {
    final TypeElement tipo = tipoDoLote(indice, "p");
    final boolean primeiro = indice == 0;
    return new BatchElements(
        primeiro ? List.of(PACOTE_P) : List.of(),
        List.of(tipo),
        metodos(tipo, LINHAS_POR_LOTE - (primeiro ? 2 : 1)));
  }

  private String paiDe(String elementId) throws SQLException {
    return lido("SELECT parent_pk FROM code_element WHERE element_id = ?", elementId);
  }

  @Test
  void acimaDoLimiarGravaLotesInteirosEOsPaisSeResolvemEntreGravacoes() throws Exception {
    final long analise = novaAnalise("an_01");
    final int lotes = LOTES_ABAIXO_DO_LIMIAR + 2;
    final ElementWriter writer =
        new ElementWriter(connections, analise, List.of(MAIN), fontesDosLotes(lotes));
    writer.begin(arquivosDosLotes(lotes));
    for (int indice = 0; indice < LOTES_ABAIXO_DO_LIMIAR; indice++) {
      writer.accept(loteDeMil(indice));
    }
    assertThat(contarElementos()).isZero();

    writer.accept(loteDeMil(LOTES_ABAIXO_DO_LIMIAR));

    assertThat(contarElementos()).isEqualTo(33_000);
    assertThat(writer.pkOf(id("pkg:p"))).isEqualTo(OptionalLong.of(1));
    assertThat(writer.pkOf(id("type:p.T0"))).isEqualTo(OptionalLong.of(2));
    assertThat(writer.pkOf(id("type:p.T32"))).isEqualTo(OptionalLong.of(32_001));

    final TypeElement ultimo = tipoDoLote(33, "p");
    final List<MethodElement> metodosDoUltimo = new ArrayList<>(metodos(ultimo, 999));
    metodosDoUltimo.add(
        new MethodElement(
            id("method:p.T0#tardio()"),
            "tardio",
            "p.T0#tardio",
            id("type:p.T0"),
            caminhoDoLote(0),
            MAIN.id(),
            Set.of(),
            false,
            SourceLines.UNAVAILABLE));
    writer.accept(new BatchElements(List.of(), List.of(ultimo), metodosDoUltimo));
    assertThat(writer.pkOf(id("type:p.T33"))).isEmpty();

    writer.end(
        arquivosDosLotes(lotes),
        IntStream.range(0, lotes).mapToObj(i -> arquivo(caminhoDoLote(i), MAIN, "pkg:p")).toList());

    assertThat(contarElementos()).isEqualTo(33_000 + 1_001 + 34);
    assertThat(writer.pkOf(id("type:p.T33"))).isEqualTo(OptionalLong.of(33_001));
    assertThat(paiDe("type:p.T33")).isEqualTo("1");
    assertThat(paiDe("method:p.T0#tardio()")).isEqualTo("2");
    assertThat(paiDe("method:p.T33#m998()")).isEqualTo("33001");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  @Test
  void umaGravacaoQueFalhaNaoEnsinaOIndiceENaoDeixaNadaDelaNaTabela() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer =
        new ElementWriter(connections, analise, List.of(MAIN), fontesDosLotes(2));
    writer.begin(arquivosDosLotes(2));
    final TypeElement primeiro = tipoDoLote(0, "p");
    writer.accept(
        new BatchElements(List.of(PACOTE_P), List.of(primeiro), metodos(primeiro, 32_767)));
    assertThat(contarElementos()).isEqualTo(32_769);

    final TypeElement novo = tipoDoLote(1, "q");
    final List<MethodElement> comDuplicata = new ArrayList<>(metodos(novo, 32_766));
    comDuplicata.add(metodos(primeiro, 6).getLast());
    final BatchElements lote =
        new BatchElements(
            List.of(new PackageElement(id("pkg:q"), "q", "q")), List.of(novo), comDuplicata);

    assertThatThrownBy(() -> writer.accept(lote))
        .isInstanceOf(ElementWriter.WriteFailedException.class)
        .satisfies(
            failure ->
                assertThat(DaoTestDatabase.resultCode(failure.getCause()))
                    .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));

    assertThat(writer.pkOf(id("pkg:q"))).isEmpty();
    assertThat(writer.pkOf(id("type:q.T1"))).isEmpty();
    assertThat(writer.pkOf(id("type:p.T0"))).isEqualTo(OptionalLong.of(2));
    assertThat(contarElementos()).isEqualTo(32_769);
    assertThat(lido("SELECT count(*) FROM code_element WHERE element_id LIKE ?", "type:q.%"))
        .isEqualTo("0");
  }

  @Test
  void acceptAntesDoBeginEDefeito() throws Exception {
    final ElementWriter writer =
        new ElementWriter(connections, novaAnalise("an_01"), RAIZES, FONTES);

    assertThatThrownBy(() -> writer.accept(LOTE_MAIN)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> writer.end(FINAIS, ARQUIVOS))
        .isInstanceOf(IllegalStateException.class);
  }
}
