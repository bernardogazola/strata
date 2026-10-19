package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ModuleDependencyDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ModuleDependencyRow;
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
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class DependencyWriterTest {

  private static final SourceRoot MAIN =
      new SourceRoot(new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 6);

  private static final String A = "src/main/java/a/A.java";
  private static final String B = "src/main/java/b/B.java";
  private static final String GHOST = "src/main/java/g/Ghost.java";
  private static final String BOOTSTRAP = "src/main/java/Bootstrap.java";
  private static final String MODULE_INFO = "src/main/java/module-info.java";

  private static final long MTIME = 1_791_000_000_000L;

  private static final List<JavaSource> FONTES =
      List.of(
          new JavaSource(A, 100, MTIME),
          new JavaSource(B, 100, MTIME),
          new JavaSource(GHOST, 100, MTIME),
          new JavaSource(BOOTSTRAP, 100, MTIME),
          new JavaSource(MODULE_INFO, 100, MTIME));

  private static final List<SourceFile> PRE_PARSE =
      List.of(
          analisado(A, "a", "A"),
          analisado(B, "b", "B"),
          analisado(GHOST, "g", "Ghost"),
          analisado(BOOTSTRAP, "", "Bootstrap"),
          new SourceFile(
              MODULE_INFO,
              MAIN.id(),
              SourceFileStatus.ANALYZED,
              null,
              new SourceFile.Declarations("", List.of())));

  private static final List<SourceFile> FINAIS =
      List.of(
          PRE_PARSE.get(0),
          PRE_PARSE.get(1),
          new SourceFile(
              GHOST,
              MAIN.id(),
              SourceFileStatus.PARSE_FAILED,
              ParseFailureCause.EXTRACTION_CRASH,
              new SourceFile.Declarations("g", List.of("Ghost"))),
          PRE_PARSE.get(3),
          PRE_PARSE.get(4));

  private static final long ARQUIVO_A = 1;
  private static final long ARQUIVO_MODULO = 5;

  private static final TypeElement TIPO_A = tipo("a", "A", A);
  private static final MethodElement RUN = metodo(TIPO_A, "run");
  private static final TypeElement TIPO_B = tipo("b", "B", B);
  private static final TypeElement BOOT = tipo("", "Bootstrap", BOOTSTRAP);

  private static final CodeElementId FANTASMA = id("type:g.Ghost");
  private static final CodeElementId LISTA = id("type:java.util.List");
  private static final CodeElementId ENTRADA = id("type:java.util.Map$Entry");
  private static final CodeElementId FOO = id("type:Foo");

  @TempDir private Path temp;

  private RepositoryCache cache;
  private SqliteConnections connections;

  private static CodeElementId id(String value) {
    return new CodeElementId(value);
  }

  private static SourceFile analisado(String caminho, String pacote, String tipo) {
    return new SourceFile(
        caminho,
        MAIN.id(),
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations(pacote, List.of(tipo)));
  }

  private static TypeElement tipo(String pacote, String nome, String caminho) {
    final String qualificado = pacote.isEmpty() ? nome : pacote + "." + nome;
    return new TypeElement(
        CodeElementId.ofType(qualificado),
        nome,
        qualificado,
        TypeKind.CLASS,
        CodeElementId.ofPackage(pacote),
        caminho,
        MAIN.id(),
        Set.of(),
        false,
        new SourceLines(1, 50));
  }

  private static MethodElement metodo(TypeElement dono, String nome) {
    return new MethodElement(
        id("method:" + dono.id().sortKey() + "#" + nome + "()"),
        nome,
        dono.qualifiedName() + "#" + nome,
        dono.id(),
        dono.filePath(),
        MAIN.id(),
        Set.of(),
        false,
        new SourceLines(10, 20));
  }

  private static BatchElements elementos(TypeElement tipo, MethodElement... metodos) {
    final String pacote = tipo.packageId().sortKey();
    return new BatchElements(
        List.of(new PackageElement(tipo.packageId(), pacote, pacote)),
        List.of(tipo),
        List.of(metodos));
  }

  private static BatchStructure lote(BatchElements elementos, ExtractedDependency... linhas) {
    return new BatchStructure(elementos, new BatchDependencies(List.of(linhas), List.of()));
  }

  private static DependencyEvidence ocorrencia(MethodElement origem, int linha) {
    return new DependencyEvidence(
        origem == null ? null : origem.id(), null, linha, ResolutionQuality.RESOLVED);
  }

  private static ExtractedDependency linha(
      TypeElement origem,
      CodeElementId alvo,
      DependencyType tipo,
      ResolutionQuality qualidade,
      boolean declarado,
      int ocorrencias) {
    final List<DependencyEvidence> evidencia =
        IntStream.rangeClosed(1, Math.min(ocorrencias, AnalysisConstants.DEPENDENCY_EVIDENCE_CAP))
            .mapToObj(linha -> new DependencyEvidence(null, null, linha, qualidade))
            .toList();
    return new ExtractedDependency(
        new Dependency(origem.id(), alvo, tipo, qualidade, ocorrencias),
        origem.filePath(),
        declarado,
        false,
        evidencia);
  }

  private static ExtractedDependency interna(
      TypeElement origem, TypeElement alvo, DependencyType tipo, ResolutionQuality qualidade) {
    return linha(origem, alvo.id(), tipo, qualidade, true, 1);
  }

  private static ExtractedDependency externa(
      TypeElement origem, CodeElementId alvo, DependencyType tipo, ResolutionQuality qualidade) {
    return linha(origem, alvo, tipo, qualidade, false, 1);
  }

  private static FileElement arquivo(String caminho) {
    return new FileElement(
        CodeElementId.ofFile(caminho),
        caminho.substring(caminho.lastIndexOf('/') + 1),
        caminho,
        caminho,
        MAIN.id(),
        null,
        false);
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

  private ElementWriter gravador(long analise, int limiar) {
    final ElementWriter writer =
        new ElementWriter(connections, analise, List.of(MAIN), FONTES, limiar);
    writer.begin(PRE_PARSE);
    return writer;
  }

  private List<String> dependencias() throws SQLException {
    return coluna(
        "SELECT s.element_id || ' -> ' || t.element_id || ' ' || d.type || ' '"
            + " || d.resolution || ' x' || d.occurrences || ' w' || d.weight"
            + " FROM dependency AS d"
            + " JOIN code_element AS s ON s.id = d.source_pk"
            + " JOIN code_element AS t ON t.id = d.target_pk"
            + " ORDER BY d.id");
  }

  private List<String> coluna(String sql) throws SQLException {
    return connections.read(c -> TestCaches.column(c, sql));
  }

  private List<ElementRow> elementosDe(long analise) throws SQLException {
    return connections.read(c -> ElementDao.list(c, analise));
  }

  private List<EvidenceRow> evidenciasDe(long analise) throws SQLException {
    return connections.read(c -> EvidenceDao.list(c, analise));
  }

  private List<ModuleDependencyRow> modulosDe(long analise) throws SQLException {
    return connections.read(c -> ModuleDependencyDao.list(c, analise));
  }

  private String lido(String sql, Object... valores) throws SQLException {
    return connections.read(c -> DaoTestDatabase.scalar(c, sql, valores));
  }

  private long pkDe(CodeElementId elemento) throws SQLException {
    return Long.parseLong(
        lido("SELECT id FROM code_element WHERE element_id = ?", elemento.value()));
  }

  private List<String> violacoesDeChaveEstrangeira() throws SQLException {
    return coluna("PRAGMA foreign_key_check");
  }

  private String contar(String tabela) throws SQLException {
    return lido("SELECT count(*) FROM " + tabela);
  }

  @Test
  void umAlvoDaMesmaDescargaEUmDeUmaDescargaAnteriorSaemDaNumeracaoEDoIndice() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);

    writer.accept(
        lote(
            elementos(TIPO_A, RUN),
            linha(TIPO_A, TIPO_A.id(), DependencyType.CALLS, ResolutionQuality.RESOLVED, true, 2)));
    writer.accept(
        lote(
            elementos(TIPO_B),
            interna(TIPO_B, TIPO_A, DependencyType.EXTENDS, ResolutionQuality.INFERRED)));

    assertThat(dependencias())
        .containsExactly(
            "type:a.A -> type:a.A CALLS RESOLVED x2 w4",
            "type:b.B -> type:a.A EXTENDS INFERRED x1 w4");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  @Test
  void umaReferenciaParaFrenteFicaPendenteEEntraPrimeiroNaDescargaQueNumeraOAlvo()
      throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);
    final ExtractedDependency paraFrente =
        new ExtractedDependency(
            new Dependency(
                TIPO_A.id(), TIPO_B.id(), DependencyType.FIELD_TYPE, ResolutionQuality.INFERRED, 1),
            A,
            true,
            false,
            List.of(ocorrencia(RUN, 12)));

    writer.accept(lote(elementos(TIPO_A, RUN), paraFrente));

    assertThat(dependencias()).isEmpty();
    assertThat(writer.pkOf(TIPO_B.id())).isEmpty();

    writer.accept(
        lote(
            elementos(TIPO_B),
            interna(TIPO_B, TIPO_A, DependencyType.EXTENDS, ResolutionQuality.RESOLVED)));

    assertThat(dependencias())
        .containsExactly(
            "type:a.A -> type:b.B FIELD_TYPE INFERRED x1 w3",
            "type:b.B -> type:a.A EXTENDS RESOLVED x1 w4");
    assertThat(evidenciasDe(analise).getFirst())
        .isEqualTo(
            new EvidenceRow(1, pkDe(RUN.id()), null, ARQUIVO_A, 12, ResolutionQuality.RESOLVED));
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  private static ElementRow externo(long pk, CodeElementId id, String rotulo) {
    return new ElementRow(
        pk,
        id,
        ElementKind.TYPE,
        null,
        rotulo,
        id.sortKey(),
        id.sortKey(),
        null,
        "",
        true,
        false,
        null,
        null,
        null,
        null);
  }

  @Test
  void umExternoECriadoUmaVezEmOrdemDePontoDeCodigoEReaproveitadoDepois() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);

    writer.accept(
        lote(
            elementos(TIPO_A, RUN),
            externa(TIPO_A, LISTA, DependencyType.RETURN_TYPE, ResolutionQuality.RESOLVED),
            externa(TIPO_A, ENTRADA, DependencyType.FIELD_TYPE, ResolutionQuality.RESOLVED),
            externa(TIPO_A, FOO, DependencyType.CALLS, ResolutionQuality.UNRESOLVED_EXTERNAL)));
    writer.accept(
        lote(
            elementos(TIPO_B),
            externa(TIPO_B, LISTA, DependencyType.PARAMETER_TYPE, ResolutionQuality.RESOLVED)));

    assertThat(elementosDe(analise).stream().filter(ElementRow::isExternal).toList())
        .containsExactly(
            externo(4, FOO, "Foo"), externo(5, LISTA, "List"), externo(6, ENTRADA, "Entry"));
    assertThat(dependencias())
        .containsExactly(
            "type:a.A -> type:java.util.List RETURN_TYPE RESOLVED x1 w2",
            "type:a.A -> type:java.util.Map$Entry FIELD_TYPE RESOLVED x1 w3",
            "type:a.A -> type:Foo CALLS UNRESOLVED_EXTERNAL x1 w2",
            "type:b.B -> type:java.util.List PARAMETER_TYPE RESOLVED x1 w1");
    assertThat(writer.pkOf(LISTA)).isEmpty();
    assertThat(writer.pkOf(TIPO_B.id())).isEqualTo(OptionalLong.of(8));
  }

  @Test
  void umAlvoNaoDeclaradoComOIdDeUmTipoDoRepositorioEDefeitoENadaDaDescargaFica() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(lote(elementos(BOOT)));

    assertThatThrownBy(
            () ->
                writer.accept(
                    lote(
                        elementos(TIPO_B),
                        externa(
                            TIPO_B,
                            BOOT.id(),
                            DependencyType.CALLS,
                            ResolutionQuality.UNRESOLVED_EXTERNAL))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("type:Bootstrap");

    assertThat(writer.pkOf(TIPO_B.id())).isEmpty();
    assertThat(contar("code_element")).isEqualTo("2");
    assertThat(lido("SELECT is_external FROM code_element WHERE element_id = 'type:Bootstrap'"))
        .isEqualTo("0");
    assertThat(contar("dependency")).isEqualTo("0");
  }

  @Test
  void noEndOQueAindaPendeEDescartadoEContadoEOQueOLoteFinalNumeraEntra() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 7);
    writer.accept(
        lote(
            elementos(TIPO_A, RUN),
            interna(TIPO_A, TIPO_B, DependencyType.CALLS, ResolutionQuality.INFERRED),
            linha(TIPO_A, FANTASMA, DependencyType.CALLS, ResolutionQuality.INFERRED, true, 1)));
    writer.accept(lote(elementos(TIPO_B)));
    assertThat(dependencias()).isEmpty();
    assertThat(writer.droppedDependencies()).isZero();

    writer.end(FINAIS, List.of(arquivo(A), arquivo(B), arquivo(GHOST)));

    assertThat(dependencias()).containsExactly("type:a.A -> type:b.B CALLS INFERRED x1 w2");
    assertThat(writer.droppedDependencies()).isEqualTo(1);
    assertThat(lido("SELECT count(*) FROM code_element WHERE element_id = ?", FANTASMA.value()))
        .isEqualTo("0");
    assertThat(lido("SELECT count(*) FROM code_element WHERE kind = 'FILE'")).isEqualTo("3");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  @Test
  void oMetodoDeOrigemViraChaveEUmMetodoSemLinhaOuAusenteViraNull() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);
    final MethodElement semLinha =
        metodo(TIPO_A, "l" + "o".repeat(AnalysisConstants.MAX_ELEMENT_ID_BYTES) + "ngo");
    final ExtractedDependency chamada =
        new ExtractedDependency(
            new Dependency(TIPO_A.id(), LISTA, DependencyType.CALLS, ResolutionQuality.RESOLVED, 3),
            A,
            false,
            false,
            List.of(ocorrencia(RUN, 7), ocorrencia(semLinha, 9), ocorrencia(null, 0)));

    writer.accept(lote(elementos(TIPO_A, RUN), chamada));

    final long run = pkDe(RUN.id());
    assertThat(evidenciasDe(analise))
        .containsExactly(
            new EvidenceRow(1, run, null, ARQUIVO_A, 7, ResolutionQuality.RESOLVED),
            new EvidenceRow(1, null, null, ARQUIVO_A, 9, ResolutionQuality.RESOLVED),
            new EvidenceRow(1, null, null, ARQUIVO_A, null, ResolutionQuality.RESOLVED));
  }

  @Test
  void umMetodoDeOrigemQueCabeNoTetoEFaltaNaDescargaEDefeito() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);
    final MethodElement ausente = metodo(TIPO_A, "ausente");
    final ExtractedDependency chamada =
        new ExtractedDependency(
            new Dependency(TIPO_A.id(), LISTA, DependencyType.CALLS, ResolutionQuality.RESOLVED, 1),
            A,
            false,
            false,
            List.of(ocorrencia(ausente, 7)));

    assertThatThrownBy(() -> writer.accept(lote(elementos(TIPO_A, RUN), chamada)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ausente.id().value());

    assertThat(contar("code_element")).isEqualTo("0");
    assertThat(contar("dependency")).isEqualTo("0");
  }

  @Test
  void umRequiresRepetidoNoMesmoArquivoEntraUmaVez() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 1);
    final BatchElements semElementos = new BatchElements(List.of(), List.of(), List.of());

    writer.accept(
        new BatchStructure(
            semElementos,
            new BatchDependencies(
                List.of(),
                List.of(
                    new ModuleDependency("com.acme", "java.sql", false, false, MODULE_INFO, 3),
                    new ModuleDependency("com.acme", "com.acme.api", true, true, MODULE_INFO, 0),
                    new ModuleDependency("com.acme", "java.sql", true, false, MODULE_INFO, 5)))));

    assertThat(modulosDe(analise))
        .containsExactly(
            new ModuleDependencyRow(1, ARQUIVO_MODULO, "com.acme", "java.sql", false, false, 3),
            new ModuleDependencyRow(
                2, ARQUIVO_MODULO, "com.acme", "com.acme.api", true, true, null));
    assertThat(contar("dependency")).isEqualTo("0");
  }

  @Test
  void oCorteContaAsLinhasDeTodaTabelaENaoSoOsElementos() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 4);

    writer.accept(
        lote(
            elementos(TIPO_B),
            externa(TIPO_B, LISTA, DependencyType.IMPORTS, ResolutionQuality.RESOLVED)));

    assertThat(contar("code_element")).isEqualTo("3");
    assertThat(contar("dependency_evidence")).isEqualTo("1");
  }

  @Test
  void umaGravacaoDesfeitaNaoDeixaElementoExternoDependenciaNemEvidencia() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    connections.write(
        c ->
            DaoTestDatabase.execute(
                c,
                "CREATE TEMP TRIGGER recusa BEFORE INSERT ON dependency_evidence"
                    + " BEGIN SELECT RAISE(ABORT, 'probe'); END"));

    assertThatThrownBy(
            () ->
                writer.accept(
                    lote(
                        elementos(TIPO_A, RUN),
                        externa(TIPO_A, LISTA, DependencyType.CALLS, ResolutionQuality.RESOLVED))))
        .isInstanceOf(ElementWriter.WriteFailedException.class)
        .satisfies(
            failure ->
                assertThat(DaoTestDatabase.resultCode(failure.getCause()))
                    .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER));

    assertThat(contar("code_element")).isEqualTo("0");
    assertThat(contar("dependency")).isEqualTo("0");
    assertThat(contar("dependency_evidence")).isEqualTo("0");
    assertThat(writer.pkOf(TIPO_A.id())).isEmpty();
  }

  @Test
  void semEndFicamSoDescargasInteirasECadaDependenciaTemOsDoisExtremos() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 9);
    writer.accept(
        lote(
            elementos(TIPO_A, RUN),
            interna(TIPO_A, TIPO_A, DependencyType.CALLS, ResolutionQuality.RESOLVED),
            interna(TIPO_A, TIPO_B, DependencyType.FIELD_TYPE, ResolutionQuality.INFERRED),
            externa(TIPO_A, LISTA, DependencyType.RETURN_TYPE, ResolutionQuality.RESOLVED)));
    writer.accept(
        lote(
            elementos(TIPO_B),
            interna(TIPO_B, TIPO_A, DependencyType.EXTENDS, ResolutionQuality.RESOLVED)));

    assertThat(dependencias())
        .containsExactly(
            "type:a.A -> type:a.A CALLS RESOLVED x1 w2",
            "type:a.A -> type:java.util.List RETURN_TYPE RESOLVED x1 w2");
    assertThat(contar("dependency_evidence")).isEqualTo("2");
    assertThat(contar("code_element")).isEqualTo("4");
    assertThat(lido("SELECT status FROM analysis")).isEqualTo("RUNNING");
    assertThat(violacoesDeChaveEstrangeira()).isEmpty();
  }

  @Test
  void soOsQuatroParesValidosDeQualidadeEExternoChegamAoBanco() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(lote(elementos(TIPO_B)));

    writer.accept(
        lote(
            elementos(TIPO_A),
            interna(TIPO_A, TIPO_A, DependencyType.CALLS, ResolutionQuality.RESOLVED),
            interna(TIPO_A, TIPO_B, DependencyType.FIELD_TYPE, ResolutionQuality.INFERRED),
            externa(TIPO_A, LISTA, DependencyType.RETURN_TYPE, ResolutionQuality.RESOLVED),
            externa(
                TIPO_A,
                FOO,
                DependencyType.PARAMETER_TYPE,
                ResolutionQuality.UNRESOLVED_EXTERNAL)));

    assertThat(
            coluna(
                "SELECT DISTINCT d.resolution || ',' || t.is_external FROM dependency AS d"
                    + " JOIN code_element AS t ON t.id = d.target_pk ORDER BY 1"))
        .containsExactly("INFERRED,0", "RESOLVED,0", "RESOLVED,1", "UNRESOLVED_EXTERNAL,1");
  }

  @Test
  void umParInvalidoDeQualidadeEExternoEDefeito() throws Exception {
    final ElementWriter declarado = gravador(novaAnalise("an_01"), 1);
    assertThatThrownBy(
            () ->
                declarado.accept(
                    lote(
                        elementos(TIPO_A),
                        interna(
                            TIPO_A,
                            TIPO_A,
                            DependencyType.CALLS,
                            ResolutionQuality.UNRESOLVED_EXTERNAL))))
        .isInstanceOf(IllegalStateException.class);

    final ElementWriter externo = gravador(novaAnalise("an_02"), 1);
    assertThatThrownBy(
            () ->
                externo.accept(
                    lote(
                        elementos(TIPO_A),
                        externa(TIPO_A, LISTA, DependencyType.CALLS, ResolutionQuality.INFERRED))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(contar("code_element")).isEqualTo("0");
  }

  @Test
  void aSomaDosPesosPorParEOPesoDeCadaTipoVezesAsOcorrencias() throws Exception {
    final ElementWriter writer = gravador(novaAnalise("an_01"), 1);
    writer.accept(lote(elementos(TIPO_B)));

    writer.accept(
        lote(
            elementos(TIPO_A),
            linha(TIPO_A, TIPO_B.id(), DependencyType.CALLS, ResolutionQuality.RESOLVED, true, 3),
            linha(
                TIPO_A,
                TIPO_B.id(),
                DependencyType.FIELD_TYPE,
                ResolutionQuality.INFERRED,
                true,
                2),
            linha(TIPO_A, TIPO_B.id(), DependencyType.IMPORTS, ResolutionQuality.RESOLVED, true, 1),
            linha(TIPO_A, LISTA, DependencyType.RETURN_TYPE, ResolutionQuality.RESOLVED, false, 4),
            linha(
                TIPO_A,
                LISTA,
                DependencyType.PARAMETER_TYPE,
                ResolutionQuality.RESOLVED,
                false,
                25)));

    assertThat(
            coluna(
                "SELECT t.element_id || '=' || sum(d.weight) || '/' || sum(d.occurrences)"
                    + " FROM dependency AS d JOIN code_element AS t ON t.id = d.target_pk"
                    + " GROUP BY d.source_pk, d.target_pk ORDER BY d.target_pk"))
        .containsExactly(
            "type:b.B=" + (3 * 2 + 2 * 3 + 1) + "/6",
            "type:java.util.List=" + (4 * 2 + 25) + "/29");
    assertThat(contar("dependency_evidence"))
        .isEqualTo(String.valueOf(10 + AnalysisConstants.DEPENDENCY_EVIDENCE_CAP));
  }

  @Test
  void oAcceptDeElementosEUmLoteSemDependencias() throws Exception {
    final long analise = novaAnalise("an_01");
    final ElementWriter writer = gravador(analise, 2);

    writer.accept(elementos(TIPO_B));

    assertThat(elementosDe(analise))
        .extracting(ElementRow::elementId)
        .containsExactly(TIPO_B.packageId(), TIPO_B.id());
    assertThat(contar("dependency")).isEqualTo("0");
  }
}
