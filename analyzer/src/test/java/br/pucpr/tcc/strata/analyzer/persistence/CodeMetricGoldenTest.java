package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class CodeMetricGoldenTest {

  private static final List<SourceRootKind> ORDEM_DE_VISITA =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final Path EVIDENCIA = Path.of("target", "code-metrics");

  private static final String SEM_LINHA = " <sem linha em code_metric>";

  private static final String GOLDEN_DO_CLOC = "cloc-golden.txt";

  private static final String CABECALHO_DO_CLOC = "# cloc ";

  private static final String GOLDEN_DE_CC = "cc-golden";

  private static final String RAIZ_DO_GOLDEN_DE_CC = "src/main/java";

  private static final int LINHAS_DE_CABECA = 12;

  private static final int SCHEMA_VERSION = 1;

  private static final String ANALYSIS_ID = "an_%026d".formatted(1);

  private static final Pattern CC =
      Pattern.compile("^\\s*\\*\\s*CC = (?:(.+) = )?(\\d+|none)\\s*$");

  private static final Pattern INICIALIZADORES =
      Pattern.compile("^\\s*\\*\\s*<initializers> (?:CC = (?:(.+) = )?(\\d+)|= none)\\s*$");

  private static final Pattern UNIDADES =
      Pattern.compile("^\\s*\\*\\s*units = \\[([\\d, ]*)\\]\\s*$");

  private static final Pattern CHAVE =
      Pattern.compile(
          "^\\s*\\*\\s*(ccSum|ccMax|ccMean|ccP90|unitCount|ccSumIncludingNested) = (.+)$");

  private static final Pattern ULTIMO_INTEIRO = Pattern.compile("(\\d+)\\D*$");

  private static final Pattern FIM_DA_CABECA = Pattern.compile("[{;]");

  private static final Pattern NOME_E_PARAMETROS =
      Pattern.compile("([\\p{javaJavaIdentifierPart}]+)\\s*\\(([^)]*)\\)");

  private static final String ELEMENTOS_E_METRICAS =
      """
      SELECT e.id, e.element_id, e.kind, e.parent_pk, e.start_line, e.end_line,
             f.relative_path, f.status,
             m.analysis_pk, m.loc, m.unit_count, m.cc_sum, m.cc_max, m.cc_p90
        FROM code_element e
        LEFT JOIN source_file f ON f.id = e.file_pk
        LEFT JOIN code_metric m ON m.element_pk = e.id
       WHERE e.analysis_pk = ? AND e.is_external = 0
       ORDER BY e.element_id""";

  private static final String EXTERNOS_COM_METRICA =
      """
      SELECT e.element_id
        FROM code_metric m
        JOIN code_element e ON e.id = m.element_pk
       WHERE e.analysis_pk = ? AND e.is_external = 1
       ORDER BY e.element_id""";

  private static final String PORTAO_POR_TIPO =
      "select e.kind || '|' || count(*) || '|' || sum(m.cc_sum is null) from code_metric m"
          + " join code_element e on e.id = m.element_pk group by e.kind order by e.kind";

  private static final String PORTAO_POR_PACOTE =
      "select e.element_id || '|' || m.loc || '|' || m.unit_count || '|' || m.cc_sum"
          + " from code_metric m join code_element e on e.id = m.element_pk"
          + " where e.kind = 'PACKAGE' order by e.element_id";

  @TempDir private Path temp;

  private final InstantSource clock = TestCaches.steppingClock();

  private record Metrica(
      long analysisPk, int loc, Integer unitCount, Integer ccSum, Integer ccMax, Integer ccP90) {}

  private record Linha(
      long pk,
      String id,
      String kind,
      Long parentPk,
      Integer inicio,
      Integer fim,
      String arquivo,
      String status,
      Metrica metrica) {

    String texto() {
      return metrica == null ? id + SEM_LINHA : comLoc(id, metrica.loc()) + agregados(metrica);
    }

    String agregados() {
      return metrica == null ? id + SEM_LINHA : id + agregados(metrica);
    }

    private static String agregados(Metrica metrica) {
      return agregado(metrica.unitCount(), metrica.ccSum(), metrica.ccMax(), metrica.ccP90());
    }
  }

  private record TipoGolden(
      List<Integer> unidades,
      int unitCount,
      int ccSum,
      int ccMax,
      int ccP90,
      Integer inicializadores) {}

  private record UnidadeGolden(String arquivo, int linha, Integer cc, String nome, int aridade) {}

  private record GoldenDeCc(Map<String, TipoGolden> tipos, Map<String, UnidadeGolden> unidades) {}

  private record Amarracao(
      Map<Long, TipoGolden> tipos,
      Map<Long, List<UnidadeGolden>> doMetodo,
      Map<Long, List<UnidadeGolden>> semElemento,
      Map<UnidadeGolden, Linha> dono,
      List<String> violacoes) {}

  @BeforeAll
  static void jgitHermetico() {
    HermeticSystemReader.install();
  }

  static Stream<String> fixtures() throws IOException {
    try (Stream<Path> children = Files.list(TestRepositories.corpusRoot())) {
      return children
          .filter(child -> Files.isRegularFile(child.resolve("expected.json")))
          .map(child -> child.getFileName().toString())
          .sorted()
          .toList()
          .stream();
    }
  }

  @Test
  void asDozeFixturesSaoServidas() throws IOException {
    assertThat(fixtures().toList())
        .as("sem esta asserção o teste parametrizado passaria por vacuidade sobre lista vazia")
        .hasSize(12);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void asMetricasGravadasBatemComOClocEComOContrato(String fixture) throws Exception {
    final Map<String, Integer> golden = goldenDoCloc(fixture);
    final ValidatedRepository repository = reproduzir(fixture);
    final List<SourceRoot> raizes = raizesNaOrdemDeVisita(repository);
    final PersistenceRoot persistence = PersistenceRoot.prepare(temp.toRealPath().resolve("data"));

    final long analysisPk;
    final List<Linha> linhas;
    final List<String> externos;
    try (RepositoryCache cache = abrir(persistence, repository)) {
      analysisPk = extrair(cache, repository, raizes);
      linhas = linhasGravadas(cache, analysisPk);
      externos = externosComMetrica(cache, analysisPk);
    }

    final List<Linha> arquivos = doTipo(linhas, "FILE");
    final List<String> selecionados =
        golden.keySet().stream().filter(caminho -> selecionado(caminho, raizes)).sorted().toList();
    assertThat(arquivos)
        .extracting(Linha::arquivo)
        .as(
            fixture
                + ": todo FILE tem linha no golden, e o golden cobre os .java das raízes"
                + " selecionadas; fora delas, nenhum elemento: "
                + golden.keySet().stream()
                    .filter(caminho -> !selecionado(caminho, raizes))
                    .toList())
        .containsExactlyInAnyOrderElementsOf(selecionados);
    assertThat(arquivos)
        .extracting(Linha::texto)
        .as(fixture + ": o loc de cada FILE é o code do cloc 2.10, exato")
        .containsExactlyElementsOf(
            arquivos.stream().map(arquivo -> esperadoDoArquivo(arquivo, golden)).toList());

    assertaOContrato(fixture, analysisPk, linhas, externos);
  }

  @Test
  void oGoldenDeCcGravadoBateComAsContasDosJavadocs() throws Exception {
    final Map<String, List<String>> textos = textosDoGoldenDeCc();
    final ValidatedRepository repository = repositorioDoGoldenDeCc(textos);
    final List<SourceRoot> raizes = raizesNaOrdemDeVisita(repository);
    assertThat(raizes)
        .extracting(raiz -> raiz.kind() + " " + raiz.relativePath())
        .as("o golden de CC é um lote só, em uma raiz MAIN")
        .containsExactly("MAIN " + RAIZ_DO_GOLDEN_DE_CC);
    final GoldenDeCc golden = lerGoldenDeCc(textos);
    final PersistenceRoot persistence = PersistenceRoot.prepare(temp.toRealPath().resolve("data"));

    final long analysisPk;
    final List<Linha> linhas;
    final List<String> externos;
    try (RepositoryCache cache = abrir(persistence, repository)) {
      analysisPk = extrair(cache, repository, raizes);
      linhas = linhasGravadas(cache, analysisPk);
      externos = externosComMetrica(cache, analysisPk);
    }

    final Amarracao amarracao = amarrar(golden, linhas);
    assertThat(amarracao.violacoes())
        .as(
            "cc-golden: todo TYPE e todo METHOD com linhas se amarra a um Javadoc do golden, toda"
                + " unidade do golden cai em um TYPE, e as unidades que o texto põe em um tipo cabem no"
                + " units = [...] dele")
        .isEmpty();

    final List<Linha> tipos = doTipo(linhas, "TYPE");
    assertThat(tipos)
        .extracting(Linha::agregados)
        .as("cc-golden: unit_count, cc_sum, cc_max e cc_p90 de cada TYPE = o Javadoc")
        .containsExactlyElementsOf(
            tipos.stream().map(tipo -> esperadoDoTipo(tipo, amarracao)).toList());
    final List<Linha> metodos = doTipo(linhas, "METHOD");
    assertThat(metodos)
        .extracting(Linha::agregados)
        .as(
            "cc-golden: cada METHOD soma toda unidade que a regra 11 fundiu nele; sem"
                + " unidade, unit_count 0 e CC NULL")
        .containsExactlyElementsOf(
            metodos.stream().map(metodo -> esperadoDoMetodo(metodo, amarracao)).toList());
    assertThat(tipos)
        .extracting(tipo -> identidadeGravada(tipo, linhas, amarracao))
        .as(
            "cc-golden: Σ cc_sum(METHOD de T) + Σ das unidades de T sem elemento = ccSum(T) do"
                + " golden")
        .containsExactlyElementsOf(
            tipos.stream().map(tipo -> identidadeEsperada(tipo, amarracao)).toList());

    assertaOContrato("cc-golden", analysisPk, linhas, externos);
  }

  @Test
  void simplePackagesDeixaEmTargetOCacheQueOSqlite3DoPortaoLe() throws Exception {
    TestCaches.deleteTree(EVIDENCIA);
    final PersistenceRoot evidencia = PersistenceRoot.prepare(EVIDENCIA.toAbsolutePath());
    final ValidatedRepository repository = reproduzir("simple-packages");

    try (RepositoryCache cache = abrir(evidencia, repository)) {
      extrair(cache, repository, raizesNaOrdemDeVisita(repository));
    }

    try (Stream<Path> caches = Files.list(evidencia.directory())) {
      assertThat(
              caches
                  .filter(
                      cache -> Files.isRegularFile(cache.resolve(RepositoryCache.DATABASE_FILE)))
                  .map(cache -> cache.getFileName().toString())
                  .toList())
          .as("o glob repositories/*/analysis.sqlite casa um banco só")
          .containsExactly(TestCaches.V01_KEY);
    }
    final Path banco =
        evidencia.directory().resolve(TestCaches.V01_KEY).resolve(RepositoryCache.DATABASE_FILE);
    assertThat(TestCaches.sibling(banco, "-wal"))
        .as("fechado, o arquivo se basta: um sqlite3 externo não depende de um -wal ao lado")
        .doesNotExist();
    try (Connection raw = TestCaches.raw(banco)) {
      assertThat(TestCaches.column(raw, PORTAO_POR_TIPO))
          .as(
              "a contagem por tipo: 5 FILE sem CC, 15 METHOD e 3 PACKAGE e 5 TYPE com CC,"
                  + " e nenhum alvo externo")
          .containsExactly("FILE|5|5", "METHOD|15|0", "PACKAGE|3|0", "TYPE|5|0");
      assertThat(TestCaches.column(raw, PORTAO_POR_PACOTE))
          .as(
              "a segunda: loc = Σ cloc dos FILE do pacote (package-info incluído), units e cc_sum"
                  + " = Σ dos TYPE, um por método explícito, todo CC 1")
          .containsExactly("pkg:|7|2|2", "pkg:br.pucpr|7|2|2", "pkg:br.pucpr.orders|48|11|11");
    }
  }

  private static void assertaOContrato(
      String nome, long analysisPk, List<Linha> linhas, List<String> externos) {
    assertThat(linhas)
        .filteredOn(linha -> (linha.metrica() == null) == temLinha(linha))
        .extracting(Linha::texto)
        .as(
            nome
                + ": todo PACKAGE, TYPE e METHOD interno e todo FILE decodificado tem exatamente uma"
                + " linha em code_metric; um FILE SKIPPED_TOO_LARGE, nenhuma")
        .isEmpty();
    assertThat(externos).as(nome + ": um TYPE externo não tem linha em code_metric").isEmpty();

    final List<Linha> medidas = linhas.stream().filter(linha -> linha.metrica() != null).toList();
    assertThat(medidas)
        .filteredOn(linha -> linha.metrica().analysisPk() != analysisPk)
        .extracting(Linha::texto)
        .as(nome + ": a métrica é da análise do elemento")
        .isEmpty();
    assertThat(medidas)
        .filteredOn(linha -> !padraoDeNulos(linha))
        .extracting(linha -> linha.kind() + " " + linha.texto())
        .as(
            nome
                + ": FILE só loc · PACKAGE cc_p90 NULL · TYPE nada NULL · METHOD cc_p90 NULL,"
                + " cc_sum e cc_max NULL sse unit_count 0")
        .isEmpty();

    final List<Linha> pacotes = doTipo(linhas, "PACKAGE");
    assertThat(pacotes)
        .extracting(Linha::texto)
        .as(
            nome
                + ": PACKAGE loc = Σ loc dos FILE com parent_pk = P; unit_count e cc_sum = Σ, cc_max"
                + " = max dos TYPE internos de P, 0 sem nenhum; cc_p90 NULL")
        .containsExactlyElementsOf(
            pacotes.stream().map(pacote -> esperadoDoPacote(pacote, linhas)).toList());

    assertThat(doTipo(medidas, "TYPE"))
        .filteredOn(tipo -> !cobreOsMetodos(tipo, linhas))
        .extracting(Linha::texto)
        .as(
            nome
                + ": cc_sum e unit_count de um TYPE >= Σ dos METHOD dele: a identidade da soma sem as"
                + " unidades sem elemento, que não têm linha")
        .isEmpty();
    assertThat(medidas)
        .filteredOn(linha -> "TYPE".equals(linha.kind()) || "METHOD".equals(linha.kind()))
        .filteredOn(linha -> !locCabeNasLinhas(linha))
        .extracting(linha -> linha.texto() + " lines=" + linha.inicio() + ".." + linha.fim())
        .as(nome + ": o loc de TYPE e METHOD cabe em [start_line, end_line]; 0 sem linhas")
        .isEmpty();
  }

  private static boolean temLinha(Linha linha) {
    return !"FILE".equals(linha.kind()) || !"SKIPPED_TOO_LARGE".equals(linha.status());
  }

  private static boolean padraoDeNulos(Linha linha) {
    final Metrica m = linha.metrica();
    final boolean semCc =
        m.unitCount() == null && m.ccSum() == null && m.ccMax() == null && m.ccP90() == null;
    return switch (linha.kind()) {
      case "FILE" -> semCc;
      case "PACKAGE" ->
          m.unitCount() != null && m.ccSum() != null && m.ccMax() != null && m.ccP90() == null;
      case "TYPE" ->
          m.unitCount() != null && m.ccSum() != null && m.ccMax() != null && m.ccP90() != null;
      case "METHOD" ->
          m.unitCount() != null
              && m.ccP90() == null
              && (m.ccSum() == null) == (m.unitCount() == 0)
              && (m.ccMax() == null) == (m.unitCount() == 0);
      default -> false;
    };
  }

  private static String esperadoDoPacote(Linha pacote, List<Linha> linhas) {
    final List<Metrica> tipos = metricasDosFilhos(pacote, linhas, "TYPE");
    return comLoc(
            pacote.id(),
            metricasDosFilhos(pacote, linhas, "FILE").stream().mapToInt(Metrica::loc).sum())
        + agregado(
            tipos.stream().mapToInt(metrica -> valor(metrica.unitCount())).sum(),
            tipos.stream().mapToInt(metrica -> valor(metrica.ccSum())).sum(),
            tipos.stream().mapToInt(metrica -> valor(metrica.ccMax())).max().orElse(0),
            null);
  }

  private static boolean cobreOsMetodos(Linha tipo, List<Linha> linhas) {
    final List<Metrica> metodos = metricasDosFilhos(tipo, linhas, "METHOD");
    return metodos.stream().mapToInt(metrica -> valor(metrica.ccSum())).sum()
            <= tipo.metrica().ccSum()
        && metodos.stream().mapToInt(metrica -> valor(metrica.unitCount())).sum()
            <= tipo.metrica().unitCount();
  }

  private static boolean locCabeNasLinhas(Linha linha) {
    if (linha.inicio() == null || linha.fim() == null) {
      return linha.metrica().loc() == 0;
    }
    return linha.metrica().loc() <= linha.fim() - linha.inicio() + 1;
  }

  private static String esperadoDoArquivo(Linha arquivo, Map<String, Integer> golden) {
    return temLinha(arquivo)
        ? comLoc(arquivo.id(), golden.get(arquivo.arquivo())) + agregado(null, null, null, null)
        : arquivo.id() + SEM_LINHA;
  }

  private static Map<String, Integer> goldenDoCloc(String fixture) throws IOException {
    final Path arquivo = TestRepositories.corpus(fixture).resolve(GOLDEN_DO_CLOC);
    assertThat(arquivo)
        .as(fixture + ": o " + GOLDEN_DO_CLOC + " existe (scripts/make-cloc-goldens.sh)")
        .isRegularFile();
    final List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
    assertThat(linhas.isEmpty() ? "" : linhas.getFirst())
        .as(fixture + ": a primeira linha do " + GOLDEN_DO_CLOC + " é a versão do cloc")
        .startsWith(CABECALHO_DO_CLOC);
    final Map<String, Integer> golden = new LinkedHashMap<>();
    for (final String linha : linhas.subList(1, linhas.size())) {
      final int tab = linha.lastIndexOf('\t');
      if (tab < 0) {
        throw new IllegalStateException(
            fixture + ": uma linha de cloc-golden.txt sem TAB: " + linha);
      }
      if (golden.put(linha.substring(0, tab), Integer.parseInt(linha.substring(tab + 1))) != null) {
        throw new IllegalStateException(
            fixture + ": um caminho de cloc-golden.txt se repete: " + linha);
      }
    }
    return Map.copyOf(golden);
  }

  private static boolean selecionado(String caminho, List<SourceRoot> raizes) {
    for (final SourceRoot raiz : raizes) {
      final String prefixo = raiz.relativePath();
      if (prefixo.isEmpty() || ".".equals(prefixo) || caminho.startsWith(prefixo + "/")) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, List<String>> textosDoGoldenDeCc() throws IOException {
    final Path pasta = TestRepositories.corpusRoot().getParent().resolve(GOLDEN_DE_CC);
    assertThat(pasta).as("fixtures/cc-golden/ existe: os goldens de CC à mão").isDirectory();
    final Map<String, List<String>> textos = new LinkedHashMap<>();
    try (Stream<Path> arquivos = Files.walk(pasta)) {
      for (final Path arquivo :
          arquivos
              .filter(Files::isRegularFile)
              .filter(caminho -> caminho.getFileName().toString().endsWith(".java"))
              .sorted()
              .toList()) {
        textos.put(
            RAIZ_DO_GOLDEN_DE_CC + "/" + TestRepositories.posix(pasta.relativize(arquivo)),
            Files.readAllLines(arquivo, StandardCharsets.UTF_8));
      }
    }
    assertThat(textos).as("fixtures/cc-golden/ tem ao menos um .java").isNotEmpty();
    return textos;
  }

  private ValidatedRepository repositorioDoGoldenDeCc(Map<String, List<String>> textos)
      throws Exception {
    final Path directory = temp.toRealPath().resolve("repo").resolve(GOLDEN_DE_CC);
    for (final Map.Entry<String, List<String>> texto : textos.entrySet()) {
      TestRepositories.write(directory, texto.getKey(), String.join("\n", texto.getValue()) + "\n");
    }
    try (Git git = TestRepositories.init(directory)) {
      TestRepositories.commitAll(git, "adiciona o golden de CC\n");
    }
    return validar(directory);
  }

  private static GoldenDeCc lerGoldenDeCc(Map<String, List<String>> textos) {
    final Map<String, TipoGolden> tipos = new HashMap<>();
    final Map<String, UnidadeGolden> unidades = new HashMap<>();
    for (final Map.Entry<String, List<String>> texto : textos.entrySet()) {
      final List<String> linhas = texto.getValue();
      int abertura = -1;
      for (int numero = 1; numero <= linhas.size(); numero++) {
        final String linha = linhas.get(numero - 1);
        if (abertura < 0 && linha.strip().startsWith("/**")) {
          abertura = numero;
        }
        if (abertura >= 0 && linha.contains("*/")) {
          final List<String> javadoc = linhas.subList(abertura - 1, numero);
          final String onde = texto.getKey() + ":" + numero;
          final TipoGolden tipo = tipoGolden(javadoc, onde);
          if (tipo != null) {
            tipos.put(chave(texto.getKey(), numero), tipo);
          }
          if (javadoc.stream().anyMatch(candidata -> CC.matcher(candidata).matches())) {
            unidades.put(
                chave(texto.getKey(), numero + 1),
                declaracao(texto.getKey(), linhas, numero + 1, ccDaUnidade(javadoc, onde)));
          }
          abertura = -1;
        }
      }
    }
    return new GoldenDeCc(Map.copyOf(tipos), Map.copyOf(unidades));
  }

  private static TipoGolden tipoGolden(List<String> javadoc, String onde) {
    List<Integer> unidades = null;
    Integer inicializadores = null;
    final Map<String, Integer> chaves = new HashMap<>();
    for (final String linha : javadoc) {
      final Matcher lista = UNIDADES.matcher(linha);
      if (lista.matches()) {
        unidades =
            lista.group(1).isBlank()
                ? List.of()
                : Stream.of(lista.group(1).split(","))
                    .map(String::strip)
                    .map(Integer::valueOf)
                    .toList();
      }
      final Matcher inicial = INICIALIZADORES.matcher(linha);
      if (inicial.matches() && inicial.group(2) != null) {
        inicializadores = Integer.valueOf(inicial.group(2));
      }
      final Matcher chave = CHAVE.matcher(linha);
      if (chave.matches()) {
        chaves.put(chave.group(1), ultimoInteiro(chave.group(2), onde));
      }
    }
    if (unidades == null) {
      return null;
    }
    for (final String exigida : List.of("unitCount", "ccSum", "ccMax", "ccP90")) {
      if (!chaves.containsKey(exigida)) {
        throw new IllegalStateException(
            "cc-golden " + onde + ": falta no Javadoc de um tipo a linha " + exigida);
      }
    }
    return new TipoGolden(
        unidades,
        chaves.get("unitCount"),
        chaves.get("ccSum"),
        chaves.get("ccMax"),
        chaves.get("ccP90"),
        inicializadores);
  }

  private static Integer ccDaUnidade(List<String> javadoc, String onde) {
    final List<String> valores =
        javadoc.stream()
            .map(CC::matcher)
            .filter(Matcher::matches)
            .map(unidade -> unidade.group(2))
            .toList();
    if (valores.size() != 1) {
      throw new IllegalStateException(
          "cc-golden " + onde + ": " + valores.size() + " linhas de CC");
    }
    return "none".equals(valores.getFirst()) ? null : Integer.valueOf(valores.getFirst());
  }

  private static int ultimoInteiro(String texto, String onde) {
    final Matcher inteiro = ULTIMO_INTEIRO.matcher(texto);
    if (!inteiro.find()) {
      throw new IllegalStateException("cc-golden " + onde + ": nenhum inteiro em '" + texto + "'");
    }
    return Integer.parseInt(inteiro.group(1));
  }

  private static UnidadeGolden declaracao(
      String arquivo, List<String> linhas, int linha, Integer cc) {
    final String cabeca =
        semAnotacoes(
            String.join(
                " ",
                linhas.subList(linha - 1, Math.min(linhas.size(), linha - 1 + LINHAS_DE_CABECA))));
    final Matcher fimDaCabeca = FIM_DA_CABECA.matcher(cabeca);
    final String declaracao =
        (fimDaCabeca.find() ? cabeca.substring(0, fimDaCabeca.start()) : cabeca).strip();
    if ("static".equals(declaracao)) {
      return new UnidadeGolden(arquivo, linha, cc, "<clinit>", 0);
    }
    final Matcher assinatura = NOME_E_PARAMETROS.matcher(declaracao);
    final int igual = declaracao.indexOf('=');
    if (!assinatura.find() || (igual >= 0 && igual < assinatura.start())) {
      return new UnidadeGolden(arquivo, linha, cc, null, 0);
    }
    return new UnidadeGolden(arquivo, linha, cc, assinatura.group(1), aridade(assinatura.group(2)));
  }

  private static String semAnotacoes(String texto) {
    final StringBuilder limpo = new StringBuilder();
    int i = 0;
    while (i < texto.length()) {
      if (texto.charAt(i) != '@') {
        limpo.append(texto.charAt(i++));
        continue;
      }
      i++;
      while (i < texto.length()
          && (Character.isJavaIdentifierPart(texto.charAt(i)) || texto.charAt(i) == '.')) {
        i++;
      }
      int depois = i;
      while (depois < texto.length() && Character.isWhitespace(texto.charAt(depois))) {
        depois++;
      }
      if (depois < texto.length() && texto.charAt(depois) == '(') {
        i = fechamento(texto, depois) + 1;
      }
    }
    return limpo.toString();
  }

  private static int fechamento(String texto, int abre) {
    int profundidade = 0;
    for (int i = abre; i < texto.length(); i++) {
      final char c = texto.charAt(i);
      if (c == '"' || c == '\'') {
        i++;
        while (i < texto.length() && texto.charAt(i) != c) {
          i += texto.charAt(i) == '\\' ? 2 : 1;
        }
      } else if (c == '(') {
        profundidade++;
      } else if (c == ')' && --profundidade == 0) {
        return i;
      }
    }
    return texto.length() - 1;
  }

  private static int aridade(String parametros) {
    if (parametros.isBlank()) {
      return 0;
    }
    int profundidade = 0;
    int virgulas = 0;
    for (final char c : parametros.toCharArray()) {
      if (c == '<' || c == '(' || c == '[') {
        profundidade++;
      } else if (c == '>' || c == ')' || c == ']') {
        profundidade--;
      } else if (c == ',' && profundidade == 0) {
        virgulas++;
      }
    }
    return virgulas + 1;
  }

  private static Amarracao amarrar(GoldenDeCc golden, List<Linha> linhas) {
    final List<String> violacoes = new ArrayList<>();
    final List<Linha> tipos = doTipo(linhas, "TYPE");
    final List<Linha> metodos = doTipo(linhas, "METHOD");

    final Map<Long, TipoGolden> doTipo = new HashMap<>();
    for (final Linha tipo : tipos) {
      final TipoGolden esperado =
          tipo.inicio() == null
              ? null
              : golden.tipos().get(chave(tipo.arquivo(), tipo.inicio() - 1));
      if (esperado == null) {
        violacoes.add(
            tipo.id() + ": nenhum Javadoc de tipo fecha na linha antes de " + tipo.inicio());
      } else {
        doTipo.put(tipo.pk(), esperado);
      }
    }
    if (doTipo.size() != golden.tipos().size()) {
      violacoes.add(
          golden.tipos().size() + " Javadocs de tipo no golden, " + doTipo.size() + " amarrados");
    }

    final Map<Long, List<UnidadeGolden>> doMetodo = new HashMap<>();
    final List<UnidadeGolden> soltas = new ArrayList<>(golden.unidades().values());
    for (final Linha metodo : metodos) {
      doMetodo.put(metodo.pk(), new ArrayList<>());
      if (metodo.inicio() == null) {
        continue;
      }
      final UnidadeGolden representante =
          golden.unidades().get(chave(metodo.arquivo(), metodo.inicio()));
      if (representante == null) {
        violacoes.add(
            metodo.id() + ": nenhum Javadoc de CC fecha na linha antes de " + metodo.inicio());
      } else {
        doMetodo.get(metodo.pk()).add(representante);
        soltas.remove(representante);
      }
    }

    final Map<UnidadeGolden, Linha> dono = new HashMap<>();
    for (final UnidadeGolden unidade : golden.unidades().values()) {
      donoPelaRegiao(unidade, tipos)
          .ifPresentOrElse(
              tipo -> dono.put(unidade, tipo),
              () -> violacoes.add(onde(unidade) + ": nenhuma linha TYPE contém esta declaração"));
    }
    final Map<Long, List<UnidadeGolden>> semElemento = new HashMap<>();
    soltas.sort(Comparator.comparing(CodeMetricGoldenTest::onde));
    for (final UnidadeGolden unidade : soltas) {
      final Linha tipo = dono.get(unidade);
      if (tipo == null) {
        continue;
      }
      final List<Linha> alvos =
          metodos.stream()
              .filter(metodo -> Long.valueOf(tipo.pk()).equals(metodo.parentPk()))
              .filter(metodo -> mesmaAssinatura(metodo, unidade))
              .toList();
      if (alvos.size() > 1) {
        violacoes.add(onde(unidade) + ": se funde em mais de um método: " + alvos);
      } else if (alvos.size() == 1) {
        doMetodo.get(alvos.getFirst().pk()).add(unidade);
      } else {
        semElemento.computeIfAbsent(tipo.pk(), pk -> new ArrayList<>()).add(unidade);
      }
    }

    for (final Linha tipo : tipos) {
      final TipoGolden esperado = doTipo.get(tipo.pk());
      if (esperado != null) {
        final List<Integer> postas =
            new ArrayList<>(valores(unidadesDosMetodos(tipo, doMetodo, dono)));
        postas.addAll(valores(semElemento.getOrDefault(tipo.pk(), List.of())));
        if (esperado.inicializadores() != null) {
          postas.add(esperado.inicializadores());
        }
        if (diferenca(esperado.unidades(), postas).isEmpty()) {
          violacoes.add(
              tipo.id() + ": o texto põe " + postas + " nele, units = " + esperado.unidades());
        }
      }
    }
    return new Amarracao(
        Map.copyOf(doTipo),
        congelado(doMetodo),
        congelado(semElemento),
        Map.copyOf(dono),
        List.copyOf(violacoes));
  }

  private static Map<Long, List<UnidadeGolden>> congelado(Map<Long, List<UnidadeGolden>> mapa) {
    final Map<Long, List<UnidadeGolden>> copia = new HashMap<>();
    mapa.forEach((pk, unidades) -> copia.put(pk, List.copyOf(unidades)));
    return Map.copyOf(copia);
  }

  private static Optional<Linha> donoPelaRegiao(UnidadeGolden unidade, List<Linha> tipos) {
    return tipos.stream()
        .filter(tipo -> unidade.arquivo().equals(tipo.arquivo()))
        .filter(tipo -> tipo.inicio() != null && tipo.fim() != null)
        .filter(tipo -> tipo.inicio() <= unidade.linha() && unidade.linha() <= tipo.fim())
        .max(Comparator.comparingInt(Linha::inicio));
  }

  private static boolean mesmaAssinatura(Linha metodo, UnidadeGolden unidade) {
    final String id = metodo.id();
    final int abre = id.indexOf('(', id.lastIndexOf('#'));
    final String nome = id.substring(id.lastIndexOf('#') + 1, abre);
    return nome.equals(unidade.nome())
        && aridade(id.substring(abre + 1, id.lastIndexOf(')'))) == unidade.aridade();
  }

  private static List<UnidadeGolden> unidadesDosMetodos(
      Linha tipo, Map<Long, List<UnidadeGolden>> doMetodo, Map<UnidadeGolden, Linha> dono) {
    return doMetodo.values().stream()
        .flatMap(List::stream)
        .filter(unidade -> dono.get(unidade) != null && dono.get(unidade).pk() == tipo.pk())
        .toList();
  }

  private static String esperadoDoTipo(Linha tipo, Amarracao amarracao) {
    final TipoGolden golden = amarracao.tipos().get(tipo.pk());
    return golden == null
        ? tipo.id() + " <sem Javadoc no golden>"
        : tipo.id() + agregado(golden.unitCount(), golden.ccSum(), golden.ccMax(), golden.ccP90());
  }

  private static String esperadoDoMetodo(Linha metodo, Amarracao amarracao) {
    final List<Integer> unidades = valores(amarracao.doMetodo().get(metodo.pk()));
    return metodo.id()
        + (unidades.isEmpty()
            ? agregado(0, null, null, null)
            : agregado(
                unidades.size(),
                unidades.stream().mapToInt(Integer::intValue).sum(),
                unidades.stream().mapToInt(Integer::intValue).max().orElseThrow(),
                null));
  }

  private static List<Integer> semElementoDoTipo(Linha tipo, Amarracao amarracao) {
    final TipoGolden golden = amarracao.tipos().get(tipo.pk());
    return golden == null
        ? List.of()
        : diferenca(
                golden.unidades(),
                valores(unidadesDosMetodos(tipo, amarracao.doMetodo(), amarracao.dono())))
            .orElse(List.of());
  }

  private static String identidadeEsperada(Linha tipo, Amarracao amarracao) {
    final TipoGolden golden = amarracao.tipos().get(tipo.pk());
    return tipo.id() + " = " + (golden == null ? "<sem Javadoc no golden>" : golden.ccSum());
  }

  private static String identidadeGravada(Linha tipo, List<Linha> linhas, Amarracao amarracao) {
    final int metodos =
        metricasDosFilhos(tipo, linhas, "METHOD").stream()
            .mapToInt(metrica -> valor(metrica.ccSum()))
            .sum();
    final int semElemento =
        semElementoDoTipo(tipo, amarracao).stream().mapToInt(Integer::intValue).sum();
    return tipo.id() + " = " + (metodos + semElemento);
  }

  private static List<Integer> valores(List<UnidadeGolden> unidades) {
    return unidades.stream().map(UnidadeGolden::cc).filter(cc -> cc != null).toList();
  }

  private static Optional<List<Integer>> diferenca(List<Integer> de, List<Integer> menos) {
    final List<Integer> resto = new ArrayList<>(de);
    for (final Integer valor : menos) {
      if (!resto.remove(valor)) {
        return Optional.empty();
      }
    }
    return Optional.of(List.copyOf(resto));
  }

  private static String onde(UnidadeGolden unidade) {
    return unidade.arquivo() + ":" + unidade.linha();
  }

  private static String chave(String arquivo, int linha) {
    return arquivo + ":" + linha;
  }

  private ValidatedRepository reproduzir(String fixture) throws Exception {
    final Path directory = temp.toRealPath().resolve("repo").resolve(fixture);
    TestRepositories.reproduceCorpusFixture(fixture, directory);
    return validar(directory);
  }

  private static ValidatedRepository validar(Path directory) throws IOException {
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
  }

  private static List<SourceRoot> raizesNaOrdemDeVisita(ValidatedRepository repository) {
    return repository.layout().sourceRoots().stream()
        .sorted(Comparator.comparingInt(raiz -> ORDEM_DE_VISITA.indexOf(raiz.kind())))
        .toList();
  }

  private RepositoryCache abrir(PersistenceRoot persistence, ValidatedRepository repository)
      throws CacheUnavailableException {
    return RepositoryCache.open(
            persistence,
            TestCaches.identityV01(),
            repository.snapshot().root(),
            "0.0.0-test",
            clock)
        .cache();
  }

  private long extrair(
      RepositoryCache cache, ValidatedRepository repository, List<SourceRoot> raizes)
      throws SQLException {
    final SqliteConnections connections = cache.connections();
    final long analysisPk =
        connections.write(
            c ->
                AnalysisDao.insertRunning(
                    c,
                    new NewAnalysis(
                        ANALYSIS_ID,
                        clock.millis(),
                        repository.snapshot().head().commitId(),
                        "0.0.0-test",
                        SCHEMA_VERSION,
                        AnalysisConstants.PROTOCOL_VERSION,
                        "0".repeat(64),
                        "{\"includeTests\":true}",
                        null,
                        true,
                        HistoryScope.ALL,
                        null)));
    final ElementWriter writer =
        new ElementWriter(
            connections, analysisPk, raizes, repository.snapshot().inventory().javaSources());
    new SpoonModelBuilder()
        .analyze(repository, raizes, new StructureExtractor(raizes, writer), () -> false);
    return analysisPk;
  }

  @FunctionalInterface
  private interface Leitor<T> {
    T ler(ResultSet rows) throws SQLException;
  }

  private static <T> List<T> ler(
      RepositoryCache cache, String sql, long analysisPk, Leitor<T> leitor) throws SQLException {
    return cache
        .connections()
        .read(
            c -> {
              final List<T> valores = new ArrayList<>();
              try (PreparedStatement statement = c.prepareStatement(sql)) {
                statement.setLong(1, analysisPk);
                try (ResultSet rows = statement.executeQuery()) {
                  while (rows.next()) {
                    valores.add(leitor.ler(rows));
                  }
                }
              }
              return List.copyOf(valores);
            });
  }

  private static List<Linha> linhasGravadas(RepositoryCache cache, long analysisPk)
      throws SQLException {
    return ler(cache, ELEMENTOS_E_METRICAS, analysisPk, CodeMetricGoldenTest::linha);
  }

  private static List<String> externosComMetrica(RepositoryCache cache, long analysisPk)
      throws SQLException {
    return ler(cache, EXTERNOS_COM_METRICA, analysisPk, rows -> rows.getString(1));
  }

  private static Linha linha(ResultSet rows) throws SQLException {
    final Long metricaDaAnalise = longo(rows, 9);
    final Metrica metrica =
        metricaDaAnalise == null
            ? null
            : new Metrica(
                metricaDaAnalise,
                rows.getInt(10),
                inteiro(rows, 11),
                inteiro(rows, 12),
                inteiro(rows, 13),
                inteiro(rows, 14));
    return new Linha(
        rows.getLong(1),
        rows.getString(2),
        rows.getString(3),
        longo(rows, 4),
        inteiro(rows, 5),
        inteiro(rows, 6),
        rows.getString(7),
        rows.getString(8),
        metrica);
  }

  private static Long longo(ResultSet rows, int coluna) throws SQLException {
    final long valor = rows.getLong(coluna);
    return rows.wasNull() ? null : valor;
  }

  private static Integer inteiro(ResultSet rows, int coluna) throws SQLException {
    final Long valor = longo(rows, coluna);
    return valor == null ? null : Math.toIntExact(valor);
  }

  private static List<Linha> doTipo(List<Linha> linhas, String kind) {
    return linhas.stream().filter(linha -> kind.equals(linha.kind())).toList();
  }

  private static List<Metrica> metricasDosFilhos(Linha pai, List<Linha> linhas, String kind) {
    return doTipo(linhas, kind).stream()
        .filter(linha -> Long.valueOf(pai.pk()).equals(linha.parentPk()))
        .map(Linha::metrica)
        .filter(metrica -> metrica != null)
        .toList();
  }

  private static String comLoc(String id, Integer loc) {
    return id + " loc=" + loc;
  }

  private static String agregado(Integer unitCount, Integer ccSum, Integer ccMax, Integer ccP90) {
    return " units=%s sum=%s max=%s p90=%s"
        .formatted(ou(unitCount), ou(ccSum), ou(ccMax), ou(ccP90));
  }

  private static String ou(Integer valor) {
    return valor == null ? "NULL" : valor.toString();
  }

  private static int valor(Integer valor) {
    return valor == null ? 0 : valor;
  }
}
