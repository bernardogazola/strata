package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingElementSink.ids;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DuplicateBatchingTest {

  private static final List<SourceRootKind> ORDEM_DE_VISITA =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String MENOR = "src/main/java/q/A.java";
  private static final String MAIOR = "src/main/java/q/Z.java";

  private static final Map<String, String> PAR_NUM_PEDACO =
      Map.of(
          "src/main/java/p/F1.java",
          "package p;\n\nclass F1 {}\n",
          "src/main/java/p/F2.java",
          "package p;\n\nclass F2 {}\n",
          "src/main/java/p/F3.java",
          "package p;\n\nclass F3 {}\n",
          MENOR,
          "package q;\n\nclass Dup {\n  void a() {}\n}\n",
          MAIOR,
          "package q;\n\nclass Dup {\n  void z() {}\n}\n");

  @TempDir private Path temp;

  private record Execucao(
      SourceAnalysis analysis, CodeElementExtractor extractor, RecordingElementSink sink) {

    String vencedor() {
      return sink.type("type:q.Dup").filePath();
    }
  }

  private static Execucao analisar(ValidatedRepository repository) {
    final List<SourceRoot> roots =
        repository.layout().sourceRoots().stream()
            .sorted(Comparator.comparingInt(root -> ORDEM_DE_VISITA.indexOf(root.kind())))
            .toList();
    final RecordingElementSink sink = new RecordingElementSink();
    final CodeElementExtractor extractor = new CodeElementExtractor(roots, sink);
    final SourceAnalysis analysis =
        new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    return new Execucao(analysis, extractor, sink);
  }

  private Execucao analisar(String pasta, Map<String, String> arquivos) throws Exception {
    return analisar(SourceTrees.repository(temp.resolve(pasta), arquivos));
  }

  private static void assertOMenorVenceSemBissecao(Execucao run) {
    assertThat(run.vencedor())
        .as("o caminho menor é visitado primeiro e fica com o tipo")
        .isEqualTo(MENOR);
    assertThat(ids(run.sink().methods()))
        .contains("method:q.Dup#a()")
        .doesNotContain("method:q.Dup#z()");
    assertThat(run.extractor().issues())
        .containsExactly(SourceIssue.duplicateElement(MAIOR, MENOR));
    assertThat(run.analysis().parseFailures()).isZero();
    final SourceAnalysis.Diagnostics diagnostics = run.analysis().diagnostics();
    assertThat(diagnostics.quarantinedBatches()).as("nenhuma quarentena").isZero();
    assertThat(diagnostics.batches()).as("o par em dois lotes").isEqualTo(2);
    assertThat(diagnostics.builds())
        .as("um build por lote: nenhum build de bisseção")
        .isEqualTo(diagnostics.batches());
  }

  @Test
  void umParNoMesmoPedacoSeSeparaAntesDoPrimeiroBuildEOCaminhoMenorVence() throws Exception {
    final Execucao run = analisar("par", PAR_NUM_PEDACO);

    assertOMenorVenceSemBissecao(run);
    assertThat(run.analysis().diagnostics().splitBatches())
        .as("a raiz coube em um pedaço só: a separação é do par, não do teto de bytes")
        .isZero();
  }

  @Test
  void doisArquivosSemRelacaoNaoTrocamOVencedor() throws Exception {
    final Map<String, String> antes = new LinkedHashMap<>();
    antes.put("src/main/java/p/F1.java", "package p;\n\nclass F1 {}\n");
    antes.put("src/main/java/p/F2.java", "package p;\n\nclass F2 {}\n");
    antes.put(MENOR, PAR_NUM_PEDACO.get(MENOR));
    antes.put(MAIOR, PAR_NUM_PEDACO.get(MAIOR));
    final Map<String, String> depois = new LinkedHashMap<>(antes);
    depois.put("src/main/java/r/G.java", "package r;\n\nclass G {}\n");
    depois.put("src/main/java/r/H.java", "package r;\n\nclass H {}\n");

    final Execucao semEles = analisar("antes", antes);
    final Execucao comEles = analisar("depois", depois);

    assertThat(comEles.vencedor())
        .as("o vencedor não depende de arquivos sem relação com o par")
        .isEqualTo(semEles.vencedor());
    assertOMenorVenceSemBissecao(semEles);
    assertOMenorVenceSemBissecao(comEles);
  }

  @Test
  void entreRaizesOMainVenceMesmoComOTesteNumCaminhoMenor() throws Exception {
    final String teste =
        "spring-context/src/test/java/org/springframework/mock/env/MockEnvironment.java";
    final String main =
        "spring-test/src/main/java/org/springframework/mock/env/MockEnvironment.java";
    final Execucao run =
        analisar(
            "spring",
            Map.of(
                teste,
                "package org.springframework.mock.env;\n\npublic class MockEnvironment {\n"
                    + "  void fromTest() {}\n}\n",
                main,
                "package org.springframework.mock.env;\n\npublic class MockEnvironment {\n"
                    + "  void fromMain() {}\n}\n"));

    assertThat(run.sink().type("type:org.springframework.mock.env.MockEnvironment").filePath())
        .isEqualTo(main);
    assertThat(run.extractor().issues()).containsExactly(SourceIssue.duplicateElement(teste, main));
    assertThat(run.analysis().diagnostics().builds()).isEqualTo(2);
  }

  @Test
  void umParEmPedacosDiferentesDaMesmaRaizContinuaComoHoje() throws Exception {
    final int grande = 2_000_000;
    assertThat(grande).isLessThanOrEqualTo(AnalysisConstants.MAX_SOURCE_FILE_BYTES);
    assertThat(9L * grande).isGreaterThan(AnalysisConstants.MAX_MODEL_SOURCE_BYTES);
    final Path pasta = temp.resolve("dois-pedacos");
    TestRepositories.write(pasta, MENOR, "package q;\n\nclass Dup {\n  void a() {}\n}\n");
    TestRepositories.write(pasta, MAIOR, "package q;\n\nclass Dup {\n  void z() {}\n}\n");
    for (int indice = 1; indice <= 9; indice++) {
      final String cabeca = "package q;\n\nclass B" + indice + " {}\n/*";
      final String rodape = "*/\n";
      TestRepositories.write(
          pasta,
          "src/main/java/q/B" + indice + ".java",
          cabeca + "x".repeat(grande - cabeca.length() - rodape.length()) + rodape);
    }
    try (Git git = TestRepositories.init(pasta)) {
      assertThat(git.getRepository().getDirectory()).exists();
    }

    final Execucao run = analisar(SourceTrees.validated(pasta));

    final SourceAnalysis.Diagnostics diagnostics = run.analysis().diagnostics();
    assertThat(diagnostics.splitBatches())
        .as("a raiz foi dividida pelo teto de bytes")
        .isEqualTo(1);
    assertThat(diagnostics.batches()).isEqualTo(2);
    assertThat(diagnostics.builds()).isEqualTo(2);
    assertThat(diagnostics.quarantinedBatches()).isZero();
    assertThat(run.vencedor()).isEqualTo(MENOR);
    assertThat(run.extractor().issues())
        .containsExactly(SourceIssue.duplicateElement(MAIOR, MENOR));
  }
}
