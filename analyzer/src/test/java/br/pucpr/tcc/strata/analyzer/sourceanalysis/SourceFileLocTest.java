package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceFileLocTest {

  @TempDir private Path temp;

  @Test
  void todoArquivoDecodificadoNoPreParseTemLocEONuncaLidoNao() throws Exception {
    final Path directory = temp.resolve("pre-parse");
    final String grande = "src/main/java/p/Grande.java";
    final String virouPasta = "src/main/java/p/VirouPasta.java";
    TestRepositories.write(
        directory,
        grande,
        "// " + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES) + "\npackage p;\n");
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory,
            Map.of(
                "src/main/java/p/Analisado.java",
                "package p;\n\n// c\nclass Analisado {\n  int x;\n}\n",
                "src/main/java/p/Quebrado.java",
                "package p;\n\nclass Quebrado {\n  int x\n}\n",
                "src/main/java/p/Lanca.java",
                "package p;\n\nclass Lanca {\n  void call(new Thing(_)) {}\n}\n",
                "src/main/java/p/Fundo.java",
                "package p;\n\nclass Fundo { void f() { " + "{".repeat(80_000) + " int x = 1 ",
                "src/main/java/p/Vazio.java",
                "",
                "src/main/java/p/SoComentario.java",
                "/* nada */\n// nada\n",
                virouPasta,
                "package p;\n\nclass VirouPasta {}\n"));
    Files.delete(directory.resolve(virouPasta));
    Files.createDirectory(directory.resolve(virouPasta));

    final SourceAnalysis analysis = analyze(repository, batch -> {});

    assertThat(desfechos(analysis))
        .containsEntry("src/main/java/p/Analisado.java", "ANALYZED loc 4")
        .containsEntry("src/main/java/p/Quebrado.java", "PARSE_FAILED SYNTAX_ERROR loc 4")
        .containsEntry("src/main/java/p/Lanca.java", "PARSE_FAILED PRE_PARSE_CRASH loc 4")
        .containsEntry("src/main/java/p/Fundo.java", "PARSE_FAILED NESTING_TOO_DEEP loc 2")
        .as("vazio e só comentário: 0 é uma medida, não a falta dela")
        .containsEntry("src/main/java/p/Vazio.java", "ANALYZED loc 0")
        .containsEntry("src/main/java/p/SoComentario.java", "ANALYZED loc 0")
        .as("nunca decodificados: sem loc")
        .containsEntry(grande, "SKIPPED_TOO_LARGE loc null")
        .containsEntry(virouPasta, "PARSE_FAILED UNREADABLE loc null")
        .hasSize(8);
  }

  @Test
  void oDesfechoDoLoteGuardaOLocDoPreParseQuandoFalhaEApagaQuandoNaoLeu() throws Exception {
    final Path directory = temp.resolve("batch");
    final String extracao = "src/main/java/a/Extracao.java";
    final String cresceu = "src/test/java/b/Cresceu.java";
    final String virouLink = "src/test/java/b/VirouLink.java";
    final String aninhou = "src/test/java/b/Aninhou.java";
    final String quebrou = "src/test/java/b/Quebrou.java";
    final Map<String, String> fontes = new TreeMap<>();
    fontes.put("src/main/java/a/A.java", "package a;\n\npublic class A {}\n");
    fontes.put("src/test/java/b/Outro.java", "package b;\n\nclass Outro {}\n");
    for (final String arquivo : new String[] {extracao, cresceu, virouLink, aninhou, quebrou}) {
      fontes.put(arquivo, declaracao(arquivo));
    }
    final ValidatedRepository repository = SourceTrees.repository(directory, fontes);
    final Path fora =
        TestRepositories.write(
            temp.resolve("fora"), "Fora.java", "package b;\n\nclass Fora {\n  int x;\n}\n");
    final AtomicBoolean mudou = new AtomicBoolean();

    final SourceAnalysis analysis =
        analyze(
            repository,
            batch -> {
              batch.perCompilationUnit(
                  (file, unit) -> {
                    if (file.equals(extracao)) {
                      throw new IllegalStateException("a extração lança nesta unidade");
                    }
                    return file;
                  });
              if (batch.root().relativePath().equals("src/main/java") && !mudou.getAndSet(true)) {
                mudar(
                    () -> {
                      TestRepositories.write(directory, cresceu, grandeDemais());
                      Files.delete(directory.resolve(virouLink));
                      Files.createSymbolicLink(directory.resolve(virouLink), fora);
                      TestRepositories.write(
                          directory,
                          aninhou,
                          "package b;\n\nclass Aninhou {\n void f() { " + "{".repeat(80_000));
                      TestRepositories.write(
                          directory, quebrou, "package b;\n\nclass Quebrou {\n  int x\n}\n");
                    });
              }
            });

    assertThat(desfechos(analysis))
        .containsEntry("src/main/java/a/A.java", "ANALYZED loc 2")
        .containsEntry("src/test/java/b/Outro.java", "ANALYZED loc 2")
        .as("falhou depois de lido: fica o loc do pré-parse")
        .containsEntry(extracao, "PARSE_FAILED EXTRACTION_CRASH loc 2")
        .containsEntry(aninhou, "PARSE_FAILED NESTING_TOO_DEEP loc 2")
        .containsEntry(quebrou, "PARSE_FAILED DROPPED_BY_SPOON loc 2")
        .as("o desfecho final diz que não foi lido: o loc cai")
        .containsEntry(cresceu, "SKIPPED_TOO_LARGE loc null")
        .containsEntry(virouLink, "PARSE_FAILED UNREADABLE loc null")
        .hasSize(7);
  }

  private static String declaracao(String arquivo) {
    final String[] partes = arquivo.split("/");
    final String pacote = partes[partes.length - 2];
    final String tipo = partes[partes.length - 1].replace(".java", "");
    return "package " + pacote + ";\n\nclass " + tipo + " {}\n";
  }

  private static String grandeDemais() {
    final String declaracao = "package b;\n\nclass Cresceu {}\n";
    return "//"
        + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES - declaracao.length() - 2)
        + "\n"
        + declaracao;
  }

  private static void mudar(Mudanca mudanca) {
    try {
      mudanca.aplicar();
    } catch (IOException falha) {
      throw new UncheckedIOException(falha);
    }
  }

  @FunctionalInterface
  private interface Mudanca {
    void aplicar() throws IOException;
  }

  private static SourceAnalysis analyze(ValidatedRepository repository, BatchVisitor visitor) {
    return new SpoonModelBuilder()
        .analyze(repository, repository.layout().sourceRoots(), visitor, SourceTrees.NEVER);
  }

  private static Map<String, String> desfechos(SourceAnalysis analysis) {
    final Map<String, String> desfechos = new TreeMap<>();
    for (final SourceFile file : analysis.files()) {
      final String causa = file.cause() == null ? "" : " " + file.cause();
      desfechos.put(file.relativePath(), file.status() + causa + " loc " + file.loc());
    }
    return desfechos;
  }
}
