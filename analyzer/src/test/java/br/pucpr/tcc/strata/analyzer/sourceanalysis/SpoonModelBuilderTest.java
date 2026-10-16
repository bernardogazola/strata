package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.visitor.filter.TypeFilter;

final class SpoonModelBuilderTest {

  @TempDir private Path temp;

  @Test
  void aResolucaoNaoEnxergaOsJarsDoProprioAnalisador() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("libs"),
            Map.of(
                "src/main/java/p/UsesLibs.java",
                """
                package p;

                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.eclipse.jgit.lib.Repository;

                class UsesLibs {
                  ObjectMapper mapper;
                  Repository repository;
                  java.util.List<String> names;

                  boolean probe() throws Exception {
                    return mapper.readTree("x").isArray();
                  }
                }
                """));
    final Map<String, Boolean> resolved = new TreeMap<>();
    final List<String> readTreeReturnType = new ArrayList<>();

    analyzeAll(
        repository,
        batch -> {
          final CtType<?> type = onlyType(batch, "UsesLibs");
          for (final String field : List.of("mapper", "repository", "names")) {
            resolved.put(field, type.getField(field).getType().getTypeDeclaration() != null);
          }
          type.getElements(new TypeFilter<>(CtInvocation.class)).stream()
              .filter(call -> call.getExecutable().getSimpleName().equals("readTree"))
              .forEach(call -> readTreeReturnType.add(String.valueOf(call.getType())));
        });

    assertThat(resolved)
        .containsEntry("mapper", false)
        .containsEntry("repository", false)
        .as("o JDK continua visível: java.util.List é tipo-sombra")
        .containsEntry("names", true);
    assertThat(readTreeReturnType)
        .as("JsonNode só viria do jar do Jackson que o ECJ teria achado no java.class.path")
        .containsExactly("null");
  }

  @Test
  void aConformidade25ModelaAsConstrucoesDoJava25SemCorromper() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("modern"),
            Map.of(
                "src/main/java/p/Shapes.java",
                """
                package p;

                import module java.base;

                public sealed interface Shapes permits Shapes.Circle, Shapes.Square {
                  record Circle(double radius) implements Shapes {}

                  record Square(double side) implements Shapes {}

                  static double area(Shapes shape) {
                    return switch (shape) {
                      case Circle(var radius) -> Math.PI * radius * radius;
                      case Square(double side) -> side * side;
                    };
                  }
                }
                """,
                "src/main/java/p/Flexible.java",
                """
                package p;

                class Flexible extends Base {
                  Flexible(int value) {
                    if (value < 0) {
                      throw new IllegalArgumentException();
                    }
                    int doubled = value * 2;
                    super(doubled);
                  }
                }

                class Base {
                  Base(int value) {}
                }
                """));
    final List<String> facts = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch -> {
              final CtType<?> shapes = onlyType(batch, "Shapes");
              facts.add("sealed=" + shapes.hasModifier(ModifierKind.SEALED));
              facts.add(
                  "records="
                      + shapes.getNestedTypes().stream()
                          .filter(CtRecord.class::isInstance)
                          .count());
              final CtConstructor<?> constructor =
                  onlyType(batch, "Flexible")
                      .getElements(new TypeFilter<>(CtConstructor.class))
                      .getFirst();
              facts.add(
                  "firstStatement="
                      + constructor
                          .getBody()
                          .getStatements()
                          .getFirst()
                          .getClass()
                          .getSimpleName());
            });

    assertThat(analysis.parseFailures()).isZero();
    assertThat(facts).containsExactly("sealed=true", "records=2", "firstStatement=CtIfImpl");
  }

  @Test
  void umArquivoAcimaDoTetoFicaDeForaComAvisoEOrestoEAnalisado() throws Exception {
    final Path directory = temp.resolve("large");
    final String large = "src/main/java/p/Generated.java";
    final String padding = "// " + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES) + "\n";
    TestRepositories.write(directory, large, padding + "package p;\n\nclass Generated {}\n");
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory, Map.of("src/main/java/p/Small.java", "package p;\n\nclass Small {}\n"));
    final List<String> batchFiles = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(repository, batch -> batchFiles.addAll(batch.files()));

    assertThat(status(analysis, large)).isEqualTo(SourceFileStatus.SKIPPED_TOO_LARGE);
    assertThat(analysis.issues())
        .containsExactly(SourceIssue.fileTooLarge(large, AnalysisConstants.MAX_SOURCE_FILE_BYTES));
    assertThat(analysis.totalFiles()).isEqualTo(2);
    assertThat(analysis.skippedFiles()).isEqualTo(1);
    assertThat(analysis.parseFailures()).isZero();
    assertThat(batchFiles).containsExactly("src/main/java/p/Small.java");
  }

  @Test
  void umaRaizAcimaDoTetoDeFontePorModeloViraPedacosConsecutivosSemPerderArquivo()
      throws Exception {
    final Path directory = temp.resolve("budget");
    final String padding = "// " + "x".repeat(1_990_000) + "\n";
    final List<String> files = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      final String file = "src/main/java/p/Big" + index + ".java";
      TestRepositories.write(
          directory, file, padding + "package p;\n\nclass Big" + index + " {}\n");
      files.add(file);
    }
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory, Map.of("src/main/java/p/Small.java", "package p;\n\nclass Small {}\n"));
    final List<List<String>> batches = new ArrayList<>();

    final SourceAnalysis analysis = analyzeAll(repository, batch -> batches.add(batch.files()));

    assertThat(analysis.parseFailures()).isZero();
    assertThat(analysis.diagnostics().splitBatches()).isEqualTo(1);
    assertThat(batches).hasSize(2);
    final List<String> concatenated = new ArrayList<>();
    batches.forEach(concatenated::addAll);
    files.add("src/main/java/p/Small.java");
    assertThat(concatenated).as("pedaços consecutivos na ordem de caminho").isSorted();
    assertThat(concatenated).containsExactlyInAnyOrderElementsOf(files);
    for (final List<String> batch : batches) {
      long bytes = 0;
      for (final String file : batch) {
        bytes += Files.size(directory.resolve(file));
      }
      assertThat(bytes).isLessThanOrEqualTo(AnalysisConstants.MAX_MODEL_SOURCE_BYTES);
    }
  }

  @Test
  void arquivosSemNadaAModelarSaoAnalisadosENaoContamComoFalha() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("trivia"),
            Map.of(
                "src/main/java/p/Empty.java", "",
                "src/main/java/p/Blank.java", "   \n\t\n",
                "src/main/java/p/Comment.java", "/* nada aqui */\n// nem aqui\n",
                "src/main/java/p/Semicolon.java", ";\n",
                "src/main/java/p/Real.java", "package p;\n\nclass Real {}\n"));
    final List<String> batchFiles = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(repository, batch -> batchFiles.addAll(batch.files()));

    assertThat(analysis.totalFiles()).isEqualTo(5);
    assertThat(analysis.skippedFiles()).isZero();
    assertThat(analysis.issues()).isEmpty();
    assertThat(batchFiles).containsExactly("src/main/java/p/Real.java");
  }

  @Test
  void oModuleInfoEModeladoNumLoteSoDele() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("module"),
            Map.of(
                "src/main/java/module-info.java", "module probe.mod {\n  exports p;\n}\n",
                "src/main/java/p/A.java", "package p;\n\npublic class A {\n  B b;\n}\n",
                "src/main/java/p/B.java", "package p;\n\npublic class B {}\n"));
    final List<List<String>> batches = new ArrayList<>();
    final List<String> modules = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch -> {
              batches.add(batch.files());
              batch.model().getAllModules().stream()
                  .filter(module -> !module.isUnnamedModule())
                  .map(CtModule::getSimpleName)
                  .forEach(modules::add);
            });

    assertThat(analysis.parseFailures()).isZero();
    assertThat(batches)
        .containsExactly(
            List.of("src/main/java/p/A.java", "src/main/java/p/B.java"),
            List.of("src/main/java/module-info.java"));
    assertThat(modules).containsExactly("probe.mod");
  }

  @Test
  void oCancelamentoParaDentroDaConstrucaoDoModeloENaoViraFalha() throws Exception {
    final Map<String, String> files = new LinkedHashMap<>();
    for (int index = 0; index < 50; index++) {
      files.put(
          "src/main/java/p/T" + index + ".java",
          "package p;\n\nclass T" + index + " {\n  T" + ((index + 1) % 50) + " next;\n}\n");
    }
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve("cancel"), files);
    final int insideTheBuild = files.size() + 3;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger visits = new AtomicInteger();

    assertThatThrownBy(
            () ->
                new SpoonModelBuilder()
                    .analyze(
                        repository,
                        repository.layout().sourceRoots(),
                        batch -> visits.incrementAndGet(),
                        () -> calls.incrementAndGet() >= insideTheBuild))
        .isInstanceOf(CancellationException.class);
    assertThat(visits).hasValue(0);
    assertThat(calls).hasValue(insideTheBuild);
  }

  @Test
  void oCancelamentoAlcancaAExtracaoAntesDeCadaUnidade() throws Exception {
    final Map<String, String> files = new LinkedHashMap<>();
    for (int index = 0; index < 5; index++) {
      files.put("src/main/java/p/U" + index + ".java", "package p;\n\nclass U" + index + " {}\n");
    }
    final ValidatedRepository repository =
        SourceTrees.repository(temp.resolve("extraction-cancel"), files);
    final AtomicBoolean cancel = new AtomicBoolean();
    final List<String> extracted = new ArrayList<>();

    assertThatThrownBy(
            () ->
                new SpoonModelBuilder()
                    .analyze(
                        repository,
                        repository.layout().sourceRoots(),
                        batch ->
                            batch.perCompilationUnit(
                                (file, unit) -> {
                                  extracted.add(file);
                                  cancel.set(true);
                                  return file;
                                }),
                        cancel::get))
        .isInstanceOf(CancellationException.class);
    assertThat(extracted).containsExactly("src/main/java/p/U0.java");
  }

  @Test
  void umaUnidadeQueLancaNaExtracaoViraFalhaComAvisoEAsOutrasSeguem() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("extraction"),
            Map.of(
                "src/main/java/p/A.java", "package p;\n\nclass A {}\n",
                "src/main/java/p/B.java", "package p;\n\nclass B {}\n",
                "src/main/java/p/C.java", "package p;\n\nclass C {}\n"));
    final String broken = "src/main/java/p/B.java";
    final List<List<String>> extracted = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch ->
                extracted.add(
                    batch.perCompilationUnit(
                        (file, unit) -> {
                          if (file.equals(broken)) {
                            throw new StackOverflowError("como o Spoon #6802");
                          }
                          return file;
                        })));

    assertThat(extracted)
        .containsExactly(List.of("src/main/java/p/A.java", "src/main/java/p/C.java"));
    assertThat(status(analysis, broken)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, broken)).isEqualTo(ParseFailureCause.EXTRACTION_CRASH);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(broken));
  }

  @Test
  void oLoteGuardadoDepoisDaVisitaRecusaOUso() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("kept"), Map.of("src/main/java/p/A.java", "package p;\n\nclass A {}\n"));
    final List<ModeledBatch> kept = new ArrayList<>();

    analyzeAll(repository, kept::add);

    assertThat(kept).hasSize(1);
    assertThatThrownBy(() -> kept.getFirst().model()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> kept.getFirst().perCompilationUnit((file, unit) -> file))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aMesmaArvoreEmDuasPastasDaOMesmoResultado() throws Exception {
    final Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "src/main/java/p/Clean.java", "package p;\n\nclass Clean {\n  A a;\n  B b;\n  C c;\n}\n");
    files.put(
        "src/main/java/p/A.java",
        "package p;\n\nclass A {\n  int f() {\n    int x = 1\n    return x;\n  }\n}\n");
    files.put(
        "src/main/java/p/B.java",
        "package p;\n\nclass B {\n  int f() {\n    return (1 + 2));\n  }\n}\n");
    files.put("src/main/java/p/C.java", "package p;\n\nclass C {\n  int field\n}\n");
    final List<Map<String, SourceFileStatus>> runs = new ArrayList<>();
    for (final String folder : List.of("um", "outra-pasta-qualquer", "zz", "a")) {
      final ValidatedRepository repository = SourceTrees.repository(temp.resolve(folder), files);
      runs.add(statuses(analyzeAll(repository, batch -> {})));
    }

    assertThat(runs).allSatisfy(run -> assertThat(run).isEqualTo(runs.getFirst()));
    assertThat(runs.getFirst())
        .containsEntry("src/main/java/p/A.java", SourceFileStatus.PARSE_FAILED)
        .containsEntry("src/main/java/p/B.java", SourceFileStatus.PARSE_FAILED)
        .containsEntry("src/main/java/p/C.java", SourceFileStatus.PARSE_FAILED)
        .containsEntry("src/main/java/p/Clean.java", SourceFileStatus.ANALYZED);
  }

  @Test
  void umIdentificadorEmLatin1EModeladoComOAcento() throws Exception {
    final Path directory = temp.resolve("latin1");
    final Path file = directory.resolve("src/main/java/p/Legado.java");
    Files.createDirectories(file.getParent());
    Files.write(
        file,
        "package p;\n\nclass Legado {\n  int função;\n  String s = \"ação\";\n}\n"
            .getBytes(StandardCharsets.ISO_8859_1));
    try (Git git = TestRepositories.init(directory)) {
      TestRepositories.commitAll(git, "legado\n");
    }
    final ValidatedRepository repository = SourceTrees.validated(directory);
    final List<String> fields = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch ->
                onlyType(batch, "Legado").getFields().stream()
                    .map(CtField::getSimpleName)
                    .forEach(fields::add));

    assertThat(analysis.parseFailures()).isZero();
    assertThat(fields).containsExactly("função", "s");
  }

  @Test
  void oMultiModuleTemOsTiposDoGoldenEmCadaRaizInclusiveTesteEGerado() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.fixture("multi-module", temp.toRealPath().resolve("multi-module"));
    final Map<SourceRootId, String> rootPaths = new LinkedHashMap<>();
    repository
        .layout()
        .sourceRoots()
        .forEach(root -> rootPaths.put(root.id(), root.relativePath()));
    final Map<String, String> typeToRoot = new TreeMap<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch ->
                batch
                    .model()
                    .getAllTypes()
                    .forEach(
                        type ->
                            typeToRoot.put(type.getQualifiedName(), batch.root().relativePath())));

    assertThat(analysis.parseFailures()).isZero();
    assertThat(analysis.totalFiles()).isEqualTo(10);
    assertThat(typeToRoot)
        .containsExactlyEntriesOf(
            new TreeMap<>(
                Map.of(
                    "br.pucpr.legacy.api.CatalogEndpoint", "api/src/main/java",
                    "br.pucpr.legacy.api.Main", "api/src/main/java",
                    "br.pucpr.legacy.core.Audited", "core/src/main/java",
                    "br.pucpr.legacy.core.Catalog", "core/src/main/java",
                    "br.pucpr.legacy.core.CatalogEntry", "core/src/main/java",
                    "br.pucpr.legacy.core.CatalogTest", "core/src/test/java",
                    "br.pucpr.legacy.core.Kind", "core/src/main/java",
                    "br.pucpr.legacy.core.gen.CatalogMetadata",
                        "core/target/generated-sources/annotations",
                    "br.pucpr.legacy.core.spi.MemoryStore", "core/src/main/java",
                    "br.pucpr.legacy.core.spi.Store", "core/src/main/java")));
  }

  @Test
  void umaRaizNaoSelecionadaFicaDeForaEOsArquivosDelaNaoMigramParaOutra() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.fixture("multi-module", temp.toRealPath().resolve("multi-module"));
    final SourceRoot coreMain =
        repository.layout().sourceRoots().stream()
            .filter(root -> root.relativePath().equals("core/src/main/java"))
            .findFirst()
            .orElseThrow();

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(repository, List.of(coreMain), batch -> {}, SourceTrees.NEVER);

    assertThat(analysis.totalFiles()).isEqualTo(coreMain.javaFileCount()).isEqualTo(6);
    assertThat(analysis.files()).allMatch(file -> file.rootId().equals(coreMain.id()));
  }

  @Test
  void umaRaizDeOutroRepositorioERecusada() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("one"), Map.of("src/main/java/p/A.java", "package p;\n\nclass A {}\n"));
    final SourceRoot stranger =
        new SourceRoot(SourceRootId.derive("lib/src"), "lib/src", SourceRootKind.MAIN, 1);

    assertThatThrownBy(
            () ->
                new SpoonModelBuilder()
                    .analyze(repository, List.of(stranger), batch -> {}, SourceTrees.NEVER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void osAvisosSaemNaOrdemDosArquivos() throws Exception {
    final Path directory = temp.resolve("order");
    final String padding = "// " + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES) + "\n";
    TestRepositories.write(
        directory, "src/main/java/p/A.java", padding + "package p;\nclass A {}\n");
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory,
            Map.of(
                "src/main/java/p/B.java", "package p;\nclass B {\n",
                "src/main/java/p/C.java", "package p;\nclass C {}\n",
                "src/main/java/p/D.java", "package p;\nclass D { int x }\n"));

    final SourceAnalysis analysis = analyzeAll(repository, batch -> {});

    assertThat(analysis.issues())
        .containsExactly(
            SourceIssue.fileTooLarge(
                "src/main/java/p/A.java", AnalysisConstants.MAX_SOURCE_FILE_BYTES),
            SourceIssue.parseFailed("src/main/java/p/B.java"),
            SourceIssue.parseFailed("src/main/java/p/D.java"));
    assertThat(analysis.parseFailures()).isEqualTo(2);
    assertThat(analysis.skippedFiles()).isEqualTo(3);
  }

  @Test
  void oitentaMilChavesAbertasNaoChegamAoEcjEAAnaliseTerminaLogo() throws Exception {
    final String deep = "src/main/java/p/Deep.java";
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("braces"),
            Map.of(
                deep,
                "package p;\n\nclass Deep { void f() { " + "{".repeat(80_000) + " int x = 1 "));

    final SourceAnalysis analysis =
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> analyzeAll(repository, batch -> {}));

    assertThat(status(analysis, deep)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, deep)).isEqualTo(ParseFailureCause.NESTING_TOO_DEEP);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(deep));
  }

  @Test
  void trintaMilLambdasAninhadasNaoChegamAoEcjEAAnaliseTerminaLogo() throws Exception {
    final String lambdas = "src/main/java/p/Lambdas.java";
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("lambdas"),
            Map.of(
                lambdas,
                "package p;\n\nclass Lambdas { Object f() { return "
                    + "x -> ".repeat(30_000)
                    + "1; } }\n"));

    final SourceAnalysis analysis =
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> analyzeAll(repository, batch -> {}));

    assertThat(status(analysis, lambdas)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, lambdas)).isEqualTo(ParseFailureCause.NESTING_TOO_DEEP);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(lambdas));
  }

  @Test
  void novecentosNiveisLegitimosSaoModelados() throws Exception {
    final String nested = "src/main/java/p/Nested.java";
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("nested"),
            Map.of(
                nested,
                "package p;\n\nclass Nested { void f() { "
                    + "{".repeat(900)
                    + " int x = 1; "
                    + "}".repeat(900)
                    + " } }\n"));
    final List<String> types = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch -> batch.model().getAllTypes().forEach(type -> types.add(type.getSimpleName())));

    assertThat(status(analysis, nested)).isEqualTo(SourceFileStatus.ANALYZED);
    assertThat(types).containsExactly("Nested");
  }

  @Test
  void sessentaMilLambdasLadoALadoChegamAoEcj() throws Exception {
    final String flat = "src/main/java/p/Flat.java";
    final StringBuilder source = new StringBuilder("package p;\n\nclass Flat { void f() { ");
    for (int index = 0; index < 60_000; index++) {
      source.append("Runnable r").append(index).append(" = () -> {};\n");
    }
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("flat"), Map.of(flat, source.append("} int x }\n").toString()));

    final SourceAnalysis analysis = analyzeAll(repository, batch -> {});

    assertThat(status(analysis, flat)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, flat)).isEqualTo(ParseFailureCause.SYNTAX_ERROR);
  }

  @Test
  void umArquivoQueFazOParserDoEcjLancarFalhaSozinhoEORestoEAnalisado() throws Exception {
    final String broken = "src/main/java/p/Broken.java";
    final String source = "package p;\n\nclass Broken {\n  void call(new Thing(_)) {}\n}\n";
    assertThatThrownBy(() -> SyntaxPreParser.parse("Broken.java", source.toCharArray()))
        .as("o controle: sem isto, o teste passaria com uma entrada que o ECJ já não derruba")
        .isInstanceOf(NullPointerException.class);
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("pre-parse-crash"),
            Map.of(broken, source, "src/main/java/p/Fine.java", "package p;\n\nclass Fine {}\n"));
    final List<String> batchFiles = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(repository, batch -> batchFiles.addAll(batch.files()));

    assertThat(status(analysis, broken)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, broken)).isEqualTo(ParseFailureCause.PRE_PARSE_CRASH);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(broken));
    assertThat(batchFiles).containsExactly("src/main/java/p/Fine.java");
  }

  @Test
  void umArquivoQueViraPastaDepoisDaValidacaoTerminaIlegivelComUmAviso() throws Exception {
    final Path directory = temp.resolve("became-directory");
    final String vanished = "src/main/java/p/B.java";
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory,
            Map.of(
                "src/main/java/p/A.java",
                "package p;\n\nclass A {}\n",
                vanished,
                "package p;\n\nclass B {}\n"));
    Files.delete(directory.resolve(vanished));
    Files.createDirectory(directory.resolve(vanished));

    final SourceAnalysis analysis = analyzeAll(repository, batch -> {});

    assertThat(analysis.totalFiles()).isEqualTo(2);
    assertThat(status(analysis, vanished)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, vanished)).isEqualTo(ParseFailureCause.UNREADABLE);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(vanished));
  }

  @Test
  void umArquivoQueCresceAlemDoTetoDepoisDaValidacaoFicaDeForaComUmAviso() throws Exception {
    final Path directory = temp.resolve("grew-before");
    final String grown = "src/main/java/p/B.java";
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory,
            Map.of(
                "src/main/java/p/A.java",
                "package p;\n\nclass A {}\n",
                grown,
                "package p;\n\nclass B {}\n"));
    TestRepositories.write(directory, grown, tooLarge("B"));

    final SourceAnalysis analysis = analyzeAll(repository, batch -> {});

    assertThat(analysis.totalFiles()).isEqualTo(2);
    assertThat(status(analysis, grown)).isEqualTo(SourceFileStatus.SKIPPED_TOO_LARGE);
    assertThat(analysis.issues())
        .containsExactly(SourceIssue.fileTooLarge(grown, AnalysisConstants.MAX_SOURCE_FILE_BYTES));
  }

  @Test
  void umArquivoQuebradoDepoisDoPreParseEDescartadoPeloSpoonEContadoComoFalha() throws Exception {
    final Path directory = temp.resolve("dropped");
    final String swapped = "src/test/java/b/Swapped.java";
    final ValidatedRepository repository = twoRoots(directory, swapped);
    final List<String> types = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            onFirstRoot(
                () ->
                    TestRepositories.write(
                        directory, swapped, "package b;\n\nclass Swapped { int x }\n"),
                types));

    assertThat(status(analysis, swapped)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, swapped)).isEqualTo(ParseFailureCause.DROPPED_BY_SPOON);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(swapped));
    assertThat(types).containsExactlyInAnyOrder("a.A", "b.Other");
  }

  @Test
  void umArquivoTrocadoPorLinkParaForaDepoisDoPreParseNuncaEModelado() throws Exception {
    final Path directory = temp.resolve("symlink");
    final String swapped = "src/test/java/b/Swapped.java";
    final ValidatedRepository repository = twoRoots(directory, swapped);
    final Path secret =
        TestRepositories.write(
            temp.resolve("outside"), "Secret.java", "package b;\n\nclass Secret {}\n");
    final List<String> types = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            onFirstRoot(
                () -> {
                  Files.delete(directory.resolve(swapped));
                  Files.createSymbolicLink(directory.resolve(swapped), secret);
                },
                types));

    assertThat(types).containsExactlyInAnyOrder("a.A", "b.Other");
    assertThat(status(analysis, swapped)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, swapped)).isEqualTo(ParseFailureCause.UNREADABLE);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(swapped));
  }

  @Test
  void umArquivoQueCresceAlemDoTetoDepoisDoPreParseFicaDeFora() throws Exception {
    final Path directory = temp.resolve("grew-after");
    final String grown = "src/test/java/b/Swapped.java";
    final ValidatedRepository repository = twoRoots(directory, grown);
    final List<String> types = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            onFirstRoot(
                () -> TestRepositories.write(directory, grown, tooLarge("Swapped")), types));

    assertThat(status(analysis, grown)).isEqualTo(SourceFileStatus.SKIPPED_TOO_LARGE);
    assertThat(analysis.issues())
        .containsExactly(SourceIssue.fileTooLarge(grown, AnalysisConstants.MAX_SOURCE_FILE_BYTES));
    assertThat(types).containsExactlyInAnyOrder("a.A", "b.Other");
  }

  @Test
  void umArquivoQuePassaDoTetoDeAninhamentoDepoisDoPreParseNaoChegaAoEcjDoSpoon() throws Exception {
    final Path directory = temp.resolve("deep-after");
    final String deep = "src/test/java/b/Swapped.java";
    final ValidatedRepository repository = twoRoots(directory, deep);
    final List<String> types = new ArrayList<>();
    final BatchVisitor visitor =
        onFirstRoot(
            () ->
                TestRepositories.write(
                    directory,
                    deep,
                    "package b;\n\nclass Swapped { void f() { " + "{".repeat(80_000)),
            types);

    final SourceAnalysis analysis =
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> analyzeAll(repository, visitor));

    assertThat(status(analysis, deep)).isEqualTo(SourceFileStatus.PARSE_FAILED);
    assertThat(cause(analysis, deep)).isEqualTo(ParseFailureCause.NESTING_TOO_DEEP);
    assertThat(types).containsExactlyInAnyOrder("a.A", "b.Other");
  }

  @Test
  void umArquivoUtf8ComBomEAnalisadoEOIdentificadorAcentuadoEModelado() throws Exception {
    final Path directory = temp.resolve("bom");
    final Path file = directory.resolve("src/main/java/p/ComBom.java");
    Files.createDirectories(file.getParent());
    final byte[] text =
        "package p;\n\nclass ComBom {\n  int função;\n}\n".getBytes(StandardCharsets.UTF_8);
    final byte[] content = new byte[text.length + 3];
    content[0] = (byte) 0xEF;
    content[1] = (byte) 0xBB;
    content[2] = (byte) 0xBF;
    System.arraycopy(text, 0, content, 3, text.length);
    Files.write(file, content);
    try (Git git = TestRepositories.init(directory)) {
      TestRepositories.commitAll(git, "bom\n");
    }
    final ValidatedRepository repository = SourceTrees.validated(directory);
    final List<String> fields = new ArrayList<>();

    final SourceAnalysis analysis =
        analyzeAll(
            repository,
            batch ->
                onlyType(batch, "ComBom").getFields().stream()
                    .map(CtField::getSimpleName)
                    .forEach(fields::add));

    assertThat(status(analysis, "src/main/java/p/ComBom.java"))
        .isEqualTo(SourceFileStatus.ANALYZED);
    assertThat(fields).containsExactly("função");
  }

  @Test
  void umaRaizNaFronteiraDoTetoMantemCadaPastaNumPedacoSo() throws Exception {
    final Path directory = temp.resolve("directories");
    final String padding = "// " + "x".repeat(1_900_000) + "\n";
    for (int index = 0; index < 5; index++) {
      TestRepositories.write(
          directory,
          "src/main/java/p/P" + index + ".java",
          padding + "package p;\n\nclass P" + index + " {}\n");
      TestRepositories.write(
          directory,
          "src/main/java/p/q/Q" + index + ".java",
          padding + "package p.q;\n\nclass Q" + index + " {}\n");
    }
    final ValidatedRepository repository =
        SourceTrees.repository(
            directory, Map.of("src/main/java/p/z.java", "package p;\n\nclass z {}\n"));
    final List<List<String>> batches = new ArrayList<>();

    final SourceAnalysis analysis = analyzeAll(repository, batch -> batches.add(batch.files()));

    assertThat(analysis.parseFailures()).isZero();
    assertThat(analysis.diagnostics().splitBatches()).isEqualTo(1);
    assertThat(batches)
        .containsExactly(
            List.of(
                "src/main/java/p/P0.java",
                "src/main/java/p/P1.java",
                "src/main/java/p/P2.java",
                "src/main/java/p/P3.java",
                "src/main/java/p/P4.java",
                "src/main/java/p/z.java"),
            List.of(
                "src/main/java/p/q/Q0.java",
                "src/main/java/p/q/Q1.java",
                "src/main/java/p/q/Q2.java",
                "src/main/java/p/q/Q3.java",
                "src/main/java/p/q/Q4.java"));
  }

  private static ValidatedRepository twoRoots(Path directory, String changed) throws Exception {
    return SourceTrees.repository(
        directory,
        Map.of(
            "src/main/java/a/A.java",
            "package a;\n\npublic class A {}\n",
            "src/test/java/b/Other.java",
            "package b;\n\nclass Other {}\n",
            changed,
            "package b;\n\nclass Swapped {}\n"));
  }

  private static BatchVisitor onFirstRoot(FileChange change, List<String> types) {
    final AtomicBoolean changed = new AtomicBoolean();
    return batch -> {
      batch.model().getAllTypes().forEach(type -> types.add(type.getQualifiedName()));
      if (batch.root().relativePath().equals("src/main/java") && !changed.getAndSet(true)) {
        try {
          change.apply();
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
    };
  }

  @FunctionalInterface
  private interface FileChange {
    void apply() throws IOException;
  }

  private static String tooLarge(String type) {
    final String declaration = "package b;\n\nclass " + type + " {}\n";
    return "//"
        + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES - declaration.length() - 2)
        + "\n"
        + declaration;
  }

  private static SourceAnalysis analyzeAll(ValidatedRepository repository, BatchVisitor visitor) {
    return new SpoonModelBuilder()
        .analyze(repository, repository.layout().sourceRoots(), visitor, SourceTrees.NEVER);
  }

  private static CtType<?> onlyType(ModeledBatch batch, String simpleName) {
    return batch.model().getAllTypes().stream()
        .filter(type -> type.getSimpleName().equals(simpleName))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, SourceFileStatus> statuses(SourceAnalysis analysis) {
    final Map<String, SourceFileStatus> statuses = new TreeMap<>();
    analysis.files().forEach(file -> statuses.put(file.relativePath(), file.status()));
    return statuses;
  }

  private static SourceFileStatus status(SourceAnalysis analysis, String file) {
    return statuses(analysis).get(file);
  }

  private static ParseFailureCause cause(SourceAnalysis analysis, String file) {
    return analysis.files().stream()
        .filter(candidate -> candidate.relativePath().equals(file))
        .findFirst()
        .orElseThrow()
        .cause();
  }
}
