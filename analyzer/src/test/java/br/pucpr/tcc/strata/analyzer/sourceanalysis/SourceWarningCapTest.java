package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceWarningCapTest {

  private static final int TETO = AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE;

  @TempDir private Path temp;

  private record Execucao(SourceAnalysis analysis, StructureExtractor extractor) {

    List<SourceIssue> issues(SourceIssue.Code code) {
      return extractor.issues().stream().filter(issue -> issue.code() == code).toList();
    }
  }

  private static Execucao extrair(ValidatedRepository repository, List<SourceRoot> roots) {
    final StructureExtractor extractor =
        new StructureExtractor(roots, new RecordingStructureSink());
    final SourceAnalysis analysis =
        new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    return new Execucao(analysis, extractor);
  }

  private static long contador(StructureExtractor extractor, String nome) throws Exception {
    final Method accessor;
    try {
      accessor = StructureExtractor.class.getMethod(nome);
    } catch (NoSuchMethodException missing) {
      throw new AssertionError(
          "StructureExtractor não tem o método público "
              + nome
              + "(): a contagem real por trás do limite, que o SOURCE_ANALYSIS reporta como o contador '"
              + nome
              + "'",
          missing);
    }
    assertThat(accessor.getReturnType()).isIn(long.class, int.class);
    try {
      return ((Number) accessor.invoke(extractor)).longValue();
    } catch (InvocationTargetException thrown) {
      throw new AssertionError(thrown.getCause());
    }
  }

  private static final int K = 11;

  private static String vencedor(int tipo) {
    return "a/src/main/java/p/W" + tipo + ".java";
  }

  private static String perdedor(int raiz) {
    return "b" + raiz + "/src/main/java/p/L.java";
  }

  @Test
  void osParesRepetidosPassamDoTetoEOExtratorGuardaOsPrimeirosEContaTodos() throws Exception {
    final Map<String, String> arquivos = new LinkedHashMap<>();
    IntStream.range(0, K)
        .forEach(tipo -> arquivos.put(vencedor(tipo), "package p;\n\nclass T" + tipo + " {}\n"));
    final String todos =
        IntStream.range(0, K)
            .mapToObj(tipo -> "class T" + tipo + " {}\n")
            .collect(Collectors.joining());
    IntStream.range(0, K).forEach(raiz -> arquivos.put(perdedor(raiz), "package p;\n\n" + todos));
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve("pares"), arquivos);
    final List<SourceRoot> roots =
        repository.layout().sourceRoots().stream()
            .sorted(Comparator.comparing(SourceRoot::relativePath))
            .toList();
    assertThat(roots).hasSize(K + 1);
    final List<SourceIssue> todosOsPares = new ArrayList<>();
    for (final SourceRoot root : roots.subList(1, roots.size())) {
      final String loser = root.relativePath() + "/p/L.java";
      IntStream.range(0, K)
          .forEach(tipo -> todosOsPares.add(SourceIssue.duplicateElement(loser, vencedor(tipo))));
    }
    assertThat(todosOsPares).hasSize(121).hasSizeGreaterThan(TETO);

    final Execucao run = extrair(repository, roots);

    assertThat(run.issues(SourceIssue.Code.SOURCE_DUPLICATE_ELEMENT))
        .as("no máximo %d avisos por código, os primeiros na ordem dos avisos", TETO)
        .containsExactlyElementsOf(todosOsPares.subList(0, TETO));
    assertThat(contador(run.extractor(), "duplicateElements"))
        .as("a contagem verdadeira, acima do teto")
        .isEqualTo(todosOsPares.size());
    assertThat(contador(run.extractor(), "elementIdsTooLong")).isZero();
  }

  private static final int ARQUIVOS_LONGOS = TETO + 5;

  @Test
  void osIdsLongosDemaisPassamDoTetoEOExtratorGuardaOsPrimeirosEContaTodos() throws Exception {
    final String sufixo = "_" + "x".repeat(AnalysisConstants.MAX_ELEMENT_ID_BYTES);
    final Map<String, String> arquivos = new LinkedHashMap<>();
    IntStream.range(0, ARQUIVOS_LONGOS)
        .forEach(
            indice ->
                arquivos.put(
                    "src/main/java/p/F" + indice + ".java",
                    "package p;\n\nclass N" + indice + sufixo + " {}\n"));
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve("longos"), arquivos);

    final Execucao run = extrair(repository, repository.layout().sourceRoots());

    final List<SourceIssue> umPorArquivo =
        run.analysis().files().stream()
            .map(
                file ->
                    SourceIssue.elementIdTooLong(
                        file.relativePath(), AnalysisConstants.MAX_ELEMENT_ID_BYTES))
            .toList();
    assertThat(umPorArquivo).hasSize(ARQUIVOS_LONGOS);
    assertThat(run.issues(SourceIssue.Code.SOURCE_ELEMENT_ID_TOO_LONG))
        .as("no máximo %d avisos por código, os primeiros na ordem dos arquivos", TETO)
        .containsExactlyElementsOf(umPorArquivo.subList(0, TETO));
    assertThat(contador(run.extractor(), "elementIdsTooLong"))
        .as("a contagem verdadeira, acima do teto")
        .isEqualTo(ARQUIVOS_LONGOS);
    assertThat(contador(run.extractor(), "duplicateElements")).isZero();
  }

  private static final int COM_ERRO = TETO + 37;

  private static final Pattern TRINTA_E_SETE = Pattern.compile("\\b37\\b");

  private static final Pattern SINTAXE = Pattern.compile("syntax", Pattern.CASE_INSENSITIVE);

  @Test
  void maisDeCemArquivosComErroDeSintaxeLogamOsPrimeirosCemEUmaLinhaDeContagem() throws Exception {
    final Map<String, String> arquivos = new LinkedHashMap<>();
    IntStream.range(0, COM_ERRO)
        .forEach(
            indice ->
                arquivos.put(
                    "src/main/java/p/E" + indice + ".java",
                    "package p;\n\nclass E" + indice + " {\n  void m() {\n"));
    final ValidatedRepository repository =
        SourceTrees.repository(temp.resolve("sintaxe"), arquivos);
    final PrintStream original = System.err;
    final ByteArrayOutputStream capturado = new ByteArrayOutputStream();
    final SourceAnalysis analysis;
    System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
    try {
      analysis =
          new SpoonModelBuilder()
              .analyze(
                  repository, repository.layout().sourceRoots(), batch -> {}, SourceTrees.NEVER);
    } finally {
      System.setErr(original);
    }
    final List<String> log = capturado.toString(StandardCharsets.UTF_8).lines().toList();
    final List<String> naOrdem = analysis.files().stream().map(SourceFile::relativePath).toList();

    assertThat(analysis.parseFailures()).as("a contagem verdadeira não muda").isEqualTo(COM_ERRO);
    final List<String> porArquivo =
        log.stream()
            .filter(line -> line.contains("tem um erro de sintaxe"))
            .filter(line -> naOrdem.stream().anyMatch(line::contains))
            .toList();
    assertThat(porArquivo)
        .as("no máximo %d linhas por arquivo para a mesma causa", TETO)
        .hasSize(TETO);
    assertThat(porArquivo)
        .as("as primeiras, na ordem do pré-parse")
        .allSatisfy(
            line ->
                assertThat(naOrdem.subList(0, TETO).stream().filter(line::contains)).hasSize(1));
    assertThat(
            log.stream()
                .filter(line -> naOrdem.stream().noneMatch(line::contains))
                .filter(line -> TRINTA_E_SETE.matcher(line).find())
                .filter(line -> SINTAXE.matcher(line).find()))
        .as("uma única linha de contagem para a causa, com as %d silenciadas", COM_ERRO - TETO)
        .hasSize(1);
  }
}
