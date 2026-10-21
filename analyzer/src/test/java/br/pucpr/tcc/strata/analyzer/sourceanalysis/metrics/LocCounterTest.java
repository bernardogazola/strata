package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_BACKSLASH_LINE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_DOCSTRING_DESYNC;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_LINE_COMMENT_IN_LITERAL;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_MULTILINE_MERGE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_QUOTE_PAIRING;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_QUOTE_STAGE_DROP;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_UNTERMINATED_BLOCK;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.CLOC_ZERO_BYTE_NO_ROW;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.LONE_CR;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.MATCH;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.LocCounterTest.Veredito.VT_NOT_WHITESPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceCharsets;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import spoon.Launcher;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.support.compiler.VirtualFile;

final class LocCounterTest {

  private static final int FIXTURES = 12;

  private static final String CLOC_GOLDEN = "cloc-golden.txt";

  private static final String VERSAO_DO_CLOC = "# cloc 2.10";

  private static final int SEM_LINHA = -1;

  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private static final int BACKSLASH = 0x5C;

  private static final int VT = 0x0B;

  private static final int SUB = 0x1A;

  static Stream<String> fixtures() throws IOException {
    try (Stream<Path> children = Files.list(TestRepositories.corpusRoot())) {
      return children
          .filter(Files::isDirectory)
          .map(child -> child.getFileName().toString())
          .sorted()
          .toList()
          .stream();
    }
  }

  @Test
  void saoDozeFixturesECadaUmaTemOSeuGoldenDoCloc() throws IOException {
    final Path corpus = TestRepositories.corpusRoot();
    final List<String> todas = fixtures().toList();

    assertThat(todas)
        .as("descobertas pela pasta, nunca por 'tem um cloc-golden.txt': uma sem ele reprova aqui")
        .hasSize(FIXTURES);
    assertThat(todas)
        .allSatisfy(
            fixture -> assertThat(corpus.resolve(fixture).resolve(CLOC_GOLDEN)).isRegularFile());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oGoldenTemUmaLinhaPorJavaDaFixtureENenhumaAMais(String fixture) throws IOException {
    final Map<String, Integer> golden = golden(fixture);

    assertThat(golden.keySet())
        .as(
            fixture
                + ": o conjunto de arquivos do golden é o dos .java da fixture, nos dois sentidos")
        .containsExactlyInAnyOrderElementsOf(arquivosJava(TestRepositories.corpus(fixture)));
    assertThat(List.copyOf(golden.keySet()))
        .as(fixture + ": as linhas em ordem de ponto de código, como o LC_ALL=C sort as grava")
        .isSortedAccordingTo(CodeElementId::compareCodePoints);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oScannerReproduzOCodeDoClocEmCadaArquivoDaFixture(String fixture) throws IOException {
    final Map<String, Integer> golden = golden(fixture);
    final Path raiz = TestRepositories.corpus(fixture);

    final Map<String, Integer> medido = new LinkedHashMap<>();
    for (final String caminho : golden.keySet()) {
      medido.put(caminho, loc(Files.readAllBytes(raiz.resolve(caminho))));
    }

    assertThat(medido).as(fixture + ": loc de cada arquivo = code do cloc 2.10").isEqualTo(golden);
  }

  private static Map<String, Integer> golden(String fixture) throws IOException {
    final String texto =
        utf8Estrito(Files.readAllBytes(TestRepositories.corpus(fixture).resolve(CLOC_GOLDEN)));
    assertThat(texto)
        .as(fixture + ": a versão do cloc na primeira linha, e newline final")
        .startsWith(VERSAO_DO_CLOC + "\n")
        .endsWith("\n");

    final String[] linhas = texto.split("\n", -1);
    final Map<String, Integer> golden = new LinkedHashMap<>();
    for (int indice = 1; indice < linhas.length - 1; indice++) {
      final String[] campos = linhas[indice].split("\t", -1);
      assertThat(campos)
          .as(fixture + ": linha " + (indice + 1) + " é caminho<TAB>code")
          .hasSize(2)
          .satisfies(par -> assertThat(par[1]).containsOnlyDigits());
      assertThat(golden.put(campos[0], Integer.valueOf(campos[1])))
          .as(fixture + ": " + campos[0] + " aparece uma vez só")
          .isNull();
    }
    return golden;
  }

  private static Set<String> arquivosJava(Path raiz) throws IOException {
    final Set<String> arquivos = new HashSet<>();
    Files.walkFileTree(
        raiz,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            return ".git".equals(dir.getFileName().toString())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
              final List<String> segmentos = new ArrayList<>();
              raiz.relativize(file).forEach(segmento -> segmentos.add(segmento.toString()));
              arquivos.add(String.join("/", segmentos));
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return Set.copyOf(arquivos);
  }

  private static String utf8Estrito(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  @Test
  void umComentarioDentroDeUmaStringECodigo() {
    final LocCounter contagem = contar(bytes("String s = \"// not a comment\";\n"));

    assertThat(contagem.lines()).isEqualTo(1);
    assertThat(contagem.loc()).isEqualTo(1);
  }

  @Test
  void umTextBlockDeCincoLinhasComUmaBrancaDentroContaQuatro() {
    final byte[] vazia = bytes("String s = \"\"\"\n", "    a\n", "\n", "    b\n", "    \"\"\";\n");
    final byte[] soEspacos =
        bytes("String s = \"\"\"\n", "    a\n", "    \n", "    b\n", "    \"\"\";\n");

    assertThat(contar(vazia).lines()).isEqualTo(5);
    assertThat(contar(vazia).loc()).isEqualTo(4);
    assertThat(contar(vazia).loc(3, 3)).isZero();
    assertThat(contar(soEspacos).loc()).isEqualTo(4);
  }

  @Test
  void umArquivoVazioTemZeroLinhasEZeroDeLoc() {
    assertThat(contar(bytes()).lines()).isZero();
    assertThat(contar(bytes()).loc()).isZero();
    assertThat(contar(BOM).lines()).isZero();
    assertThat(contar(BOM).loc()).isZero();
  }

  @Test
  void aUltimaLinhaSemTerminadorConta() {
    assertThat(contar(bytes("class A {\n", "}")).lines()).isEqualTo(2);
    assertThat(contar(bytes("class A {\n", "}")).loc()).isEqualTo(2);
    assertThat(contar(bytes("int x;")).loc()).isEqualTo(1);
    assertThat(contar(bytes("class A {\n", "}\n")).lines()).isEqualTo(2);
  }

  @Test
  void umCrSozinhoTerminaUmaLinha() {
    final LocCounter contagem = contar(bytes("int x;\r", "int y;\r", "// c"));

    assertThat(contagem.lines()).isEqualTo(3);
    assertThat(contagem.loc()).isEqualTo(2);
    assertThat(contagem.loc(2, 2)).isEqualTo(1);
    assertThat(contagem.loc(3, 3)).isZero();
  }

  record Sozinho(String nome, char caractere, boolean branca) {

    @Override
    public String toString() {
      return nome;
    }
  }

  static Stream<Sozinho> caracteresSozinhos() {
    return Stream.of(
        new Sozinho("SP", ' ', true),
        new Sozinho("HT", '\t', true),
        new Sozinho("FF", '\f', true),
        new Sozinho("VT U+000B", (char) VT, false),
        new Sozinho("NBSP U+00A0", (char) 0xA0, false),
        new Sozinho("SOH U+0001, que o trim tira", (char) 0x01, false),
        new Sozinho("SUB U+001A, que o trim tira", (char) SUB, false),
        new Sozinho("FS U+001C, isWhitespace", (char) 0x1C, false),
        new Sozinho("US U+001F, isWhitespace", (char) 0x1F, false),
        new Sozinho("NEL U+0085", (char) 0x85, false),
        new Sozinho("EM SPACE U+2003, isWhitespace", (char) 0x2003, false),
        new Sozinho("LINE SEPARATOR U+2028, isWhitespace", (char) 0x2028, false),
        new Sozinho("PARAGRAPH SEPARATOR U+2029, isWhitespace", (char) 0x2029, false),
        new Sozinho("IDEOGRAPHIC SPACE U+3000, isWhitespace", (char) 0x3000, false),
        new Sozinho("ZWNBSP U+FEFF fora do começo", (char) 0xFEFF, false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("caracteresSozinhos")
  void soSpHtEFfSaoEspacoEmBranco(Sozinho sozinho) {
    final char c = sozinho.caractere();
    final LocCounter contagem = LocCounter.of(new char[] {'x', '\n', c, c, c, '\n', 'y', '\n'});

    assertThat(contagem.lines()).isEqualTo(3);
    assertThat(contagem.loc(2, 2)).isEqualTo(sozinho.branca() ? 0 : 1);
    assertThat(contagem.loc()).isEqualTo(sozinho.branca() ? 2 : 3);
  }

  private static final List<String> LINHAS_DOS_INTERVALOS =
      List.of("class A {", "  // c", "", "  int x;", "  /* a", "     b */", "  void m() {}", "}");

  private static final boolean[] TEM_CODIGO = {true, false, false, true, false, false, true, true};

  enum Terminador {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r");

    private final String texto;

    Terminador(String texto) {
      this.texto = texto;
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Terminador.class)
  void locDeUmIntervaloContaAsLinhasDeCodigoDentroDeleComQualquerTerminador(Terminador terminador) {
    final LocCounter contagem =
        contar(bytes(String.join(terminador.texto, LINHAS_DOS_INTERVALOS) + terminador.texto));

    assertThat(contagem.lines()).isEqualTo(TEM_CODIGO.length);
    assertThat(contagem.loc()).isEqualTo(4);
    assertThat(contagem.loc(1, 8)).isEqualTo(4);
    assertThat(contagem.loc(2, 3)).isZero();
    assertThat(contagem.loc(5, 6)).isZero();
    assertThat(contagem.loc(4, 7)).isEqualTo(2);
    for (int inicio = 1; inicio <= TEM_CODIGO.length; inicio++) {
      for (int fim = inicio; fim <= TEM_CODIGO.length; fim++) {
        assertThat(contagem.loc(inicio, fim))
            .as("loc(%d, %d)", inicio, fim)
            .isEqualTo(codigoEntre(inicio, fim));
      }
    }
  }

  @Test
  void locDeUmIntervaloForaDoTextoLancaENuncaCorta() {
    final LocCounter contagem = contar(bytes(String.join("\n", LINHAS_DOS_INTERVALOS) + "\n"));
    final int[][] foraDoTexto = {{0, 1}, {0, 0}, {-1, 3}, {1, 9}, {8, 9}, {9, 9}, {3, 2}, {8, 1}};

    for (final int[] intervalo : foraDoTexto) {
      assertThatThrownBy(() -> contagem.loc(intervalo[0], intervalo[1]))
          .as("loc(%d, %d)", intervalo[0], intervalo[1])
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void oTextoVazioNaoTemIntervaloNenhum() {
    final LocCounter vazio = contar(bytes());

    assertThatThrownBy(() -> vazio.loc(1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> vazio.loc(0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Terminador.class)
  void asLinhasSaoAsQueOSpoonNumera(Terminador terminador) {
    final String corpo =
        String.join(terminador.texto, "class A {", "  int x;", "  void m() {}", "}");
    for (final String texto : List.of(corpo + terminador.texto, corpo)) {
      final CtType<?> tipo = modelar(texto);
      final CtMethod<?> metodo = tipo.getMethodsByName("m").getFirst();
      final LocCounter contagem = contar(bytes(texto));

      assertThat(contagem.lines()).isEqualTo(tipo.getPosition().getEndLine()).isEqualTo(4);
      assertThat(metodo.getPosition().getLine()).isEqualTo(3);
      assertThat(contagem.loc(metodo.getPosition().getLine(), metodo.getPosition().getEndLine()))
          .isEqualTo(1);
    }
  }

  private static CtType<?> modelar(String texto) {
    final Launcher launcher = new Launcher();
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setCommentEnabled(false);
    launcher.getEnvironment().setComplianceLevel(SpoonModelBuilder.COMPLIANCE_LEVEL);
    launcher.addInputResource(new VirtualFile(texto, "A.java"));
    return launcher.buildModel().getAllTypes().iterator().next();
  }

  private static int codigoEntre(int inicio, int fim) {
    int codigo = 0;
    for (int linha = inicio; linha <= fim; linha++) {
      codigo += TEM_CODIGO[linha - 1] ? 1 : 0;
    }
    return codigo;
  }

  enum Veredito {
    MATCH,
    LONE_CR,
    VT_NOT_WHITESPACE,
    CLOC_DOCSTRING_DESYNC,
    CLOC_MULTILINE_MERGE,
    CLOC_QUOTE_PAIRING,
    CLOC_LINE_COMMENT_IN_LITERAL,
    CLOC_BACKSLASH_LINE,
    CLOC_UNTERMINATED_BLOCK,
    CLOC_QUOTE_STAGE_DROP,
    CLOC_ZERO_BYTE_NO_ROW
  }

  record Caso(String nome, byte[] bytes, int mao, int cloc, Veredito veredito) {

    @Override
    public String toString() {
      return nome;
    }
  }

  private static Caso caso(String nome, int mao, int cloc, Veredito veredito, Object... partes) {
    return new Caso(nome, bytes(partes), mao, cloc, veredito);
  }

  private static final List<Caso> CASOS =
      List.of(
          caso(
              "str_line_comment_marker",
              3,
              3,
              MATCH,
              "class A {\n",
              "  String s = \"// not a comment\";\n",
              "}\n"),
          caso(
              "str_block_open_marker",
              4,
              4,
              MATCH,
              "class A {\n",
              "  String s = \"/* not a comment\";\n",
              "  int x;\n",
              "}\n"),
          caso(
              "str_block_close_marker",
              4,
              4,
              MATCH,
              "class A {\n",
              "  String s = \"*/\";\n",
              "  /* real\n",
              "     comment */\n",
              "  int x;\n",
              "}\n"),
          caso(
              "str_both_markers",
              3,
              3,
              MATCH,
              "class A {\n",
              "  String s = \"/* not */\"; int x;\n",
              "}\n"),
          caso(
              "str_slashes_then_block_comment",
              4,
              6,
              CLOC_LINE_COMMENT_IN_LITERAL,
              "class A {\n",
              "  String u = \"http://example.com\"; /* start\n",
              "     still comment\n",
              "  */\n",
              "  int y;\n",
              "}\n"),
          caso(
              "str_quote_pair_spans_real_comment",
              4,
              6,
              CLOC_QUOTE_PAIRING,
              "class A {\n",
              "  String a = f(\"x\"); /* see \"y\"\n",
              "     still comment\n",
              "  */\n",
              "  int z;\n",
              "}\n"),
          caso(
              "str_three_apostrophes",
              4,
              6,
              CLOC_DOCSTRING_DESYNC,
              "class A {\n",
              "  String q = \"'''\";\n",
              "  // comment\n",
              "  /* block */\n",
              "  int x;\n",
              "}\n"),
          caso(
              "escaped_quote_in_string",
              5,
              5,
              MATCH,
              "class A {\n",
              "  String s = \"a\\\"// not a comment\";\n",
              "  String t = \"b\\\"/* not a comment\";\n",
              "  int x;\n",
              "}\n"),
          caso(
              "escaped_backslash_before_quote",
              4,
              4,
              MATCH,
              "class A {\n",
              "  String s = \"\\\\\"; /* real\n",
              "     comment */\n",
              "  int x;\n",
              "}\n"),
          caso(
              "char_double_quote",
              5,
              5,
              MATCH,
              "class A {\n",
              "  char q = '\"';\n",
              "  String s = \"/* not a comment */\";\n",
              "  int x;\n",
              "}\n"),
          caso(
              "char_double_quote_then_string_marker",
              4,
              4,
              MATCH,
              "class A {\n",
              "  char q = '\"'; String s = \"/*\"; // c\n",
              "  int x;\n",
              "}\n"),
          caso(
              "char_escaped_apostrophe",
              4,
              4,
              MATCH,
              "class A {\n",
              "  char q = '\\'';\n",
              "  int x;\n",
              "}\n"),
          caso(
              "char_backslash",
              4,
              4,
              MATCH,
              "class A {\n",
              "  char b = '\\\\';\n",
              "  String s = \"\\\\\"; // c\n",
              "}\n"),
          caso(
              "char_slash",
              4,
              4,
              MATCH,
              "class A {\n",
              "  char c = '/';\n",
              "  boolean b = c == '/' || c == '*';\n",
              "}\n"),
          caso(
              "apostrophe_in_comment_then_char",
              5,
              3,
              CLOC_QUOTE_PAIRING,
              "class A {\n",
              "  /* don't */ char c = 'x';\n",
              "  int y;\n",
              "  /** doc */\n",
              "  int z;\n",
              "}\n"),
          caso(
              "tb_basic",
              6,
              6,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      hello\n",
              "      world\n",
              "      \"\"\";\n",
              "}\n"),
          caso(
              "tb_empty_line_inside",
              6,
              6,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      a\n",
              "\n",
              "      b\n",
              "      \"\"\";\n",
              "}\n"),
          caso(
              "tb_whitespace_only_line_inside",
              6,
              6,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      a\n",
              "      \n",
              "      b\n",
              "      \"\"\";\n",
              "}\n"),
          caso(
              "tb_line_comment_marker_inside",
              6,
              6,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      // not a comment\n",
              "      \"\"\";\n",
              "  int x;\n",
              "}\n"),
          caso(
              "tb_block_comment_markers_inside",
              8,
              8,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      /* not a comment\n",
              "      still text\n",
              "      */ still text\n",
              "      \"\"\";\n",
              "  int x;\n",
              "}\n"),
          caso(
              "tb_escaped_triple_quote",
              6,
              8,
              CLOC_DOCSTRING_DESYNC,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      a \\\"\"\" still inside\n",
              "      \"\"\";\n",
              "  // trailing comment\n",
              "  /* trailing block */\n",
              "  int z;\n",
              "}\n"),
          caso(
              "tb_closing_followed_by_code",
              5,
              5,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      x\n",
              "      \"\"\"; int after = 1;\n",
              "  // c\n",
              "}\n"),
          caso(
              "tb_close_and_reopen_same_line",
              9,
              9,
              MATCH,
              "class A {\n",
              "  void f() {\n",
              "    g(\"\"\"\n",
              "        a\n",
              "        \"\"\", \"\"\"\n",
              "        b\n",
              "        \"\"\");\n",
              "  }\n",
              "  // c\n",
              "}\n"),
          caso(
              "tb_opening_with_trailing_spaces",
              5,
              5,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"   \n",
              "      x\n",
              "      \"\"\";\n",
              "}\n"),
          caso(
              "tb_inside_block_comment",
              3,
              3,
              MATCH,
              "class A {\n",
              "  /*\n",
              "  String s = \"\"\"\n",
              "      x // y\n",
              "      \"\"\";\n",
              "  */\n",
              "  int z;\n",
              "}\n"),
          caso(
              "tb_triple_quote_in_javadoc_once",
              3,
              7,
              CLOC_DOCSTRING_DESYNC,
              "/**\n",
              " * Text blocks open with \"\"\" and a line terminator.\n",
              " */\n",
              "class A {\n",
              "  // c\n",
              "  int x;\n",
              "}\n"),
          caso(
              "tb_triple_quote_in_line_comment",
              3,
              3,
              MATCH,
              "class A {\n",
              "  // a \"\"\" in a line comment\n",
              "  int x;\n",
              "  // trailing\n",
              "}\n"),
          caso(
              "tb_backslash_only_line",
              6,
              5,
              CLOC_BACKSLASH_LINE,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      a \\\n",
              "      \\\n",
              "      b\"\"\";\n",
              "}\n"),
          caso(
              "tb_unterminated",
              4,
              4,
              MATCH,
              "class A {\n",
              "  String s = \"\"\"\n",
              "      x\n",
              "\n",
              "      // y\n"),
          caso(
              "block_comment_empty",
              3,
              3,
              MATCH,
              "class A {\n",
              "  /**/\n",
              "  int x; /**/ int y;\n",
              "}\n"),
          caso(
              "block_comment_slash_star_slash",
              3,
              3,
              MATCH,
              "class A {\n",
              "  /*/ still a comment\n",
              "     still */\n",
              "  int y;\n",
              "}\n"),
          caso(
              "line_comment_contains_block_open",
              3,
              3,
              MATCH,
              "class A {\n",
              "  // a /* b\n",
              "  int x;\n",
              "  // c */\n",
              "}\n"),
          caso(
              "code_after_block_close_same_line",
              3,
              3,
              MATCH,
              "class A {\n",
              "  /* c */ int x;\n",
              "}\n"),
          caso(
              "code_after_multiline_block_close",
              3,
              3,
              MATCH,
              "class A {\n",
              "  /* start\n",
              "     end */ int x;\n",
              "}\n"),
          caso(
              "code_before_block_open",
              3,
              3,
              MATCH,
              "class A {\n",
              "  int x; /* start\n",
              "     end */\n",
              "}\n"),
          caso(
              "code_before_and_after_multiline_block",
              4,
              3,
              CLOC_MULTILINE_MERGE,
              "class A {\n",
              "  int x; /* start\n",
              "     end */ int y;\n",
              "}\n"),
          caso(
              "javadoc",
              4,
              4,
              MATCH,
              "/**\n",
              " * Javadoc with \"quotes\", 'apostrophes' and a // marker.\n",
              " */\n",
              "class A {\n",
              "  /** Field doc. */\n",
              "  int x;\n",
              "\n",
              "  /**\n",
              "   * Method doc.\n",
              "   */\n",
              "  void f() {}\n",
              "}\n"),
          caso(
              "block_comment_unterminated",
              1,
              3,
              CLOC_UNTERMINATED_BLOCK,
              "class A {}\n",
              "/* never closed\n",
              "int x;\n"),
          caso(
              "nested_block_comment",
              4,
              4,
              MATCH,
              "class A {\n",
              "  /* a /* b */ int x; /* c */\n",
              "  int y = 2 */* c */ 3;\n",
              "}\n"),
          caso(
              "star_slash_outside_comment",
              3,
              3,
              MATCH,
              "class A {\n",
              "  int x = 1 */ 2;\n",
              "}\n"),
          caso("crlf", 3, 3, MATCH, "class A {\r\n", "  int x;\r\n", "\r\n", "  // c\r\n", "}\r\n"),
          caso("lone_cr", 3, 1, LONE_CR, "class A {\r", "  int x;\r", "\r", "  // c\r", "}\r"),
          caso(
              "lone_cr_inside_line",
              4,
              3,
              LONE_CR,
              "class A {\n",
              "  int x; // c\r",
              "  int y;\n",
              "}\n"),
          caso(
              "mixed_endings",
              5,
              4,
              LONE_CR,
              "class A {\r\n",
              "  int x;\n",
              "  int y;\r",
              "  int z;\r\n",
              "}\n"),
          caso("no_final_newline", 3, 3, MATCH, "class A {\n", "  int x;\n", "}"),
          caso("no_final_newline_comment", 2, 2, MATCH, "class A {\n", "}\n", "// c"),
          caso("empty", 0, SEM_LINHA, CLOC_ZERO_BYTE_NO_ROW),
          caso("newline_only", 0, 0, MATCH, "\n"),
          caso("whitespace_only_file", 0, 0, MATCH, "  \n", "\t\n", "\f\n"),
          caso("comment_only", 0, 0, MATCH, "// only\n", "/* only */\n"),
          caso("bom_then_comment", 1, 1, MATCH, BOM, "// c\n", "class A {}\n"),
          caso("bom_then_code", 1, 1, MATCH, BOM, "class A {}\n"),
          caso("bom_alone_on_first_line", 1, 1, MATCH, BOM, "\n", "class A {}\n"),
          caso("bom_twice", 2, 2, MATCH, BOM, BOM, "// c\n", "class A {}\n"),
          caso(
              "windows1252_identifier",
              3,
              3,
              MATCH,
              "class A {\n",
              "  int fun",
              0xE7,
              0xE3,
              "o;\n",
              "}\n"),
          caso(
              "windows1252_nbsp_only_line",
              4,
              4,
              MATCH,
              "class A {\n",
              "  int fun",
              0xE7,
              0xE3,
              "o;\n",
              0xA0,
              "\n",
              "}\n"),
          caso(
              "windows1252_accented_comments",
              2,
              2,
              MATCH,
              "class A {\n",
              "  // coment",
              0xE1,
              "rio\n",
              "  /* a",
              0xE7,
              0xE3,
              "o */\n",
              "}\n"),
          caso("utf8_nbsp_only_line", 3, 3, MATCH, "class A {\n", 0xC2, 0xA0, "\n", "}\n"),
          caso("ff_only_line", 2, 2, MATCH, "class A {\n", "\f\n", "}\n"),
          caso("vt_only_line", 3, 2, VT_NOT_WHITESPACE, "class A {\n", VT, "\n", "}\n"),
          caso("tab_only_line", 2, 2, MATCH, "class A {\n", "\t\n", "}\n"),
          caso("nel_only_line", 3, 3, MATCH, "class A {\n", 0xC2, 0x85, "\n", "}\n"),
          caso(
              "line_separator_only_line",
              3,
              3,
              MATCH,
              "class A {\n",
              0xE2,
              0x80,
              0xA8,
              "\n",
              "}\n"),
          caso("ctrl_z_at_eof", 3, 3, MATCH, "class A {\n", "}\n", SUB),
          caso(
              "unicode_escape_line_comment",
              4,
              4,
              MATCH,
              "class A {\n",
              "  ",
              BACKSLASH,
              "u002F",
              BACKSLASH,
              "u002F not interpreted\n",
              "  int x;\n",
              "}\n"),
          caso(
              "unicode_escape_block_comment",
              3,
              3,
              MATCH,
              "class A {\n",
              "  ",
              BACKSLASH,
              "u002F",
              BACKSLASH,
              "u002A not interpreted ",
              BACKSLASH,
              "u002A",
              BACKSLASH,
              "u002F\n",
              "}\n"),
          caso(
              "unicode_escape_line_terminator_in_comment",
              2,
              2,
              MATCH,
              "class A {\n",
              "  // hidden ",
              BACKSLASH,
              "u000d int y;\n",
              "}\n"),
          caso("semicolon_only_line", 3, 3, MATCH, "class A {\n", "  ;\n", "}\n"),
          caso(
              "annotation_only_line",
              4,
              4,
              MATCH,
              "class A {\n",
              "  @Deprecated\n",
              "  void f() {}\n",
              "}\n"),
          caso("brace_only_line", 4, 4, MATCH, "class A {\n", "  void f() {\n", "  }\n", "}\n"),
          caso(
              "package_info_javadoc_and_package/package-info",
              1,
              1,
              MATCH,
              "/**\n",
              " * Package doc.\n",
              " */\n",
              "package a.b;\n"),
          caso(
              "package_info_javadoc_only/package-info",
              0,
              0,
              MATCH,
              "/**\n",
              " * Package doc only.\n",
              " */\n"),
          caso(
              "module_info/module-info",
              4,
              4,
              MATCH,
              "/** Module doc. */\n",
              "module a.b {\n",
              "  requires java.base;\n",
              "  exports a.b;\n",
              "}\n"),
          caso(
              "quote_regex_lead_quote_column0",
              5,
              4,
              CLOC_QUOTE_STAGE_DROP,
              "class A {\n",
              "  String s = \"a\" +\n",
              "\"b\"; /* c */ int x;\n",
              "  int y;\n",
              "}\n"),
          caso(
              "quote_regex_indent_repeats_after_comment",
              5,
              4,
              CLOC_QUOTE_STAGE_DROP,
              "class A {\n",
              "  String s =\n",
              "  \"a\" + b; /*  c */\n",
              "  int y;\n",
              "}\n"));

  static Stream<Caso> casos() {
    return CASOS.stream();
  }

  @Test
  void aTabelaTemOs75CasosDaOndaDeAnaliseCadaUmComNomeProprio() {
    assertThat(CASOS).hasSize(75);
    assertThat(CASOS).extracting(Caso::nome).doesNotHaveDuplicates();
  }

  @Test
  void oClocDiscordaDaMaoExatamenteNosCasosMarcadosComUmDesvio() {
    final List<String> incoerentes = new ArrayList<>();
    for (final Caso caso : CASOS) {
      if ((caso.mao() != caso.cloc()) != (caso.veredito() != MATCH)) {
        incoerentes.add(
            caso.nome() + ": mão " + caso.mao() + ", cloc " + caso.cloc() + ", " + caso.veredito());
      }
    }

    assertThat(incoerentes)
        .as("mão ≠ cloc ⇔ veredito ≠ MATCH: um desvio novo, ou um corrigido, reprova")
        .isEmpty();
  }

  @Test
  void soOArquivoDeZeroBytesFicaSemLinhaNoCloc() {
    assertThat(CASOS)
        .filteredOn(caso -> caso.cloc() == SEM_LINHA)
        .singleElement()
        .satisfies(
            caso -> {
              assertThat(caso.bytes()).isEmpty();
              assertThat(caso.veredito()).isEqualTo(CLOC_ZERO_BYTE_NO_ROW);
            });
    assertThat(CASOS)
        .filteredOn(caso -> caso.veredito() == CLOC_ZERO_BYTE_NO_ROW)
        .extracting(Caso::cloc)
        .containsExactly(SEM_LINHA);
  }

  @Test
  void todoVereditoDaClasseFechadaTemAoMenosUmCaso() {
    final Set<Veredito> usados = EnumSet.noneOf(Veredito.class);
    CASOS.forEach(caso -> usados.add(caso.veredito()));

    assertThat(usados)
        .as("um desvio sem caso não é um desvio medido")
        .containsExactlyInAnyOrder(Veredito.values());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("casos")
  void oScannerDaOValorFeitoAMao(Caso caso) {
    assertThat(loc(caso.bytes())).isEqualTo(caso.mao());
  }

  private static int loc(byte[] bytes) {
    return contar(bytes).loc();
  }

  private static LocCounter contar(byte[] bytes) {
    return LocCounter.of(SourceCharsets.decode(bytes));
  }

  private static byte[] bytes(Object... partes) {
    final ByteArrayOutputStream saida = new ByteArrayOutputStream();
    for (final Object parte : partes) {
      switch (parte) {
        case String texto -> {
          if (!StandardCharsets.US_ASCII.newEncoder().canEncode(texto)) {
            throw new IllegalArgumentException("não é ASCII: " + texto);
          }
          saida.writeBytes(texto.getBytes(StandardCharsets.US_ASCII));
        }
        case Integer valor when valor >= 0 && valor <= 0xFF -> saida.write(valor);
        case byte[] valores -> saida.writeBytes(valores);
        default -> throw new IllegalArgumentException("parte inválida: " + parte);
      }
    }
    return saida.toByteArray();
  }
}
