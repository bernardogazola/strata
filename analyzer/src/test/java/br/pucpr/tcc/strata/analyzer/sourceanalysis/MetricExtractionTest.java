package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics.CyclomaticComplexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Complexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtExpression;
import spoon.reflect.factory.CodeFactory;

final class MetricExtractionTest {

  private static final String WORKER =
      """
      package m;

      public class Worker implements Runnable {
        static int a;

        static {
          if (a > 0) {
            a = 1;
          }
        }

        void spawn(boolean later) {
          new Thread(new Runnable() {
            @Override
            public void run() {
              while (later) {
                a++;
              }
            }
          }).start();
        }

        @Override
        public void run() {
          if (a > 1) {
            a = 0;
          }
        }

        static {
          a = a > 0 ? 1 : 2;
        }
      }
      """;

  private static final Map<String, String> DOLLAR_COLLISION =
      Map.of(
          "src/main/java/g/Dollar.java",
          """
          package g;

          class Foo$Bar {
            int pick(boolean b) {
              return b ? 1 : 0;
            }
          }
          """,
          "src/main/java/g/Foo.java",
          """
          package g;

          public class Foo {
            static class Bar {
              int pick(boolean b) {
                if (b) {
                  return 1;
                }
                return b && !b ? 2 : 0;
              }
            }

            void own() {}
          }
          """);

  private static final int DEEP_CHAIN = 20_000;

  private static final long SMALL_STACK_BYTES = 64 * 1024L;

  private static final int SAME_BATCH_ATTEMPTS = 20;

  @TempDir private Path temp;

  @Test
  void asMetricasDeCadaLoteSaoExatamenteOsTiposEMetodosDele() throws Exception {
    final RecordingStructureSink sink =
        analyze(SourceTrees.fixture("multi-module", temp.toRealPath().resolve("multi-module")));

    assertThat(sink.accepted()).as("um lote por raiz").hasSizeGreaterThan(1);
    for (final BatchStructure batch : sink.accepted()) {
      final List<CodeElementId> elements = new ArrayList<>();
      batch.elements().types().forEach(type -> elements.add(type.id()));
      batch.elements().methods().forEach(method -> elements.add(method.id()));
      assertThat(batch.metrics())
          .extracting(CodeMetric::element)
          .as("uma métrica por TYPE e por METHOD do lote, no lote do elemento")
          .containsExactlyInAnyOrderElementsOf(elements);
    }
    assertThat(sink.metrics()).as("o fixture tem métricas").isNotEmpty();
  }

  @Test
  void umFqnRepetidoNaMesmaRaizFicaComAsMetricasDoVencedor() throws Exception {
    boolean sameBatch = false;
    for (int attempt = 0; attempt < SAME_BATCH_ATTEMPTS && !sameBatch; attempt++) {
      final RecordingStructureSink sink =
          analyze(temp.resolve("attempt-" + attempt), DOLLAR_COLLISION);
      assertThat(sink.metric("type:g.Foo$Bar"))
          .isEqualTo(metric("type:g.Foo$Bar", 5, new Complexity(2, 2, 2, 1)));
      assertThat(sink.metric("method:g.Foo$Bar#pick(boolean)"))
          .isEqualTo(metric("method:g.Foo$Bar#pick(boolean)", 3, new Complexity(2, 2, 2, 1)));
      assertThat(sink.metric("method:g.Foo$Bar#<init>()").complexity()).isNull();
      assertThat(sink.metric("type:g.Foo").complexity()).isEqualTo(new Complexity(1, 1, 1, 1));
      assertMetricsAreTheElements(sink);
      sameBatch = sink.accepted().size() == 1;
    }

    assertThat(sameBatch)
        .as("ao menos uma de " + SAME_BATCH_ATTEMPTS + " tentativas em um lote só")
        .isTrue();
  }

  @Test
  void aPassadaGuardaCadaSitioComOIdQueElaJaCalculou() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("repository"), Map.of("src/main/java/m/Worker.java", WORKER));
    final List<SourceRoot> roots = repository.layout().sourceRoots();
    final CodeElementExtractor extractor =
        new CodeElementExtractor(roots, new RecordingElementSink());
    final List<String> executables = new ArrayList<>();
    final List<String> types = new ArrayList<>();
    final List<Boolean> sameElements = new ArrayList<>();

    new SpoonModelBuilder()
        .analyze(
            repository,
            roots,
            new BatchVisitor() {
              @Override
              public void visit(ModeledBatch batch) {
                batch.perCompilationUnit(
                    (path, unit) -> {
                      final UnitExtraction.Walked walked = extractor.walk(batch, path, unit);
                      sameElements.add(
                          walked.elements().equals(extractor.extract(batch, path, unit)));
                      walked.executables().forEach(site -> executables.add(describe(site)));
                      walked.types().forEach(site -> types.add(describe(site)));
                      return null;
                    });
              }
            },
            SourceTrees.NEVER);

    assertThat(sameElements).as("extract(unit) == walk(unit).elements()").containsExactly(true);
    assertThat(executables)
        .containsExactlyInAnyOrder(
            "6 method:m.Worker#<clinit>()",
            "12 method:m.Worker#spawn(boolean)",
            "0 -",
            "14 method:m.Worker#run()",
            "23 method:m.Worker#run()",
            "30 method:m.Worker#<clinit>()",
            "0 method:m.Worker#<init>()");
    assertThat(types).containsExactly("Worker type:m.Worker element", " type:m.Worker");
  }

  @Test
  void umFqnRepetidoEmDuasRaizesFicaComAsMetricasDoPrimeiroLote() throws Exception {
    final RecordingStructureSink sink =
        analyze(
            Map.of(
                "mod1/src/main/java/a/Y.java",
                "package a;\n\npublic class Y {\n  int pick(boolean b) {\n    return b ? 1 : 0;\n"
                    + "  }\n}\n",
                "mod2/src/main/java/a/Y.java",
                "package a;\n\npublic class Y {\n  int pick(boolean b) {\n    if (b) {\n"
                    + "      return 1;\n    }\n    return b || !b ? 2 : 0;\n  }\n}\n"));

    assertThat(sink.accepted()).as("um lote por raiz").hasSize(2);
    assertThat(sink.metric("type:a.Y"))
        .isEqualTo(metric("type:a.Y", 5, new Complexity(2, 2, 2, 1)));
    assertThat(sink.metric("method:a.Y#pick(boolean)"))
        .isEqualTo(metric("method:a.Y#pick(boolean)", 3, new Complexity(2, 2, 2, 1)));
    assertThat(sink.accepted().getLast().metrics()).as("o lote do perdedor").isEmpty();
    assertMetricsAreTheElements(sink);
  }

  @Test
  void umMetodoAcimaDoTetoNaoTemMetricaMasAsUnidadesDeleContamNoTipo() throws Exception {
    final String longName = "L".repeat(300);
    final String parameters =
        IntStream.range(0, 14)
            .mapToObj(index -> longName + " a" + index)
            .collect(Collectors.joining(", "));
    final RecordingStructureSink sink =
        analyze(
            Map.of(
                "src/main/java/p/Wide.java",
                "package p;\n\npublic class Wide {\n  void ok() {}\n\n  void first("
                    + parameters
                    + ") {\n    if (a0 == null) {\n      return;\n    }\n  }\n}\n\nclass "
                    + longName
                    + " {}\n"));

    assertThat(sink.metrics())
        .extracting(metric -> metric.element().value())
        .containsExactlyInAnyOrder(
            "type:p." + longName,
            "method:p." + longName + "#<init>()",
            "type:p.Wide",
            "method:p.Wide#<init>()",
            "method:p.Wide#ok()");
    assertThat(sink.metric("type:p.Wide").complexity()).isEqualTo(new Complexity(3, 2, 2, 2));
    assertMetricsAreTheElements(sink);
  }

  @Test
  void umMetodoFundidoSomaAsUnidadesEMedeAsLinhasDoRepresentante() throws Exception {
    final RecordingStructureSink sink = analyze(Map.of("src/main/java/m/Worker.java", WORKER));

    assertThat(sink.metric("method:m.Worker#<clinit>()"))
        .isEqualTo(metric("method:m.Worker#<clinit>()", 5, new Complexity(4, 2, 2, 2)));
    assertThat(sink.metric("method:m.Worker#run()"))
        .isEqualTo(metric("method:m.Worker#run()", 6, new Complexity(4, 2, 2, 2)));
    assertThat(sink.metric("method:m.Worker#spawn(boolean)"))
        .isEqualTo(metric("method:m.Worker#spawn(boolean)", 10, new Complexity(1, 1, 1, 1)));
    assertThat(sink.metric("method:m.Worker#<init>()"))
        .isEqualTo(new CodeMetric(new CodeElementId("method:m.Worker#<init>()"), 0, null));
    assertThat(sink.metric("type:m.Worker"))
        .isEqualTo(metric("type:m.Worker", 27, new Complexity(9, 2, 2, 5)));
    assertMetricsAreTheElements(sink);
  }

  @Test
  void oStaticDeUmaClasseLocalNaoEhUnidadePorqueOSpoonOPerde() throws Exception {
    final RecordingStructureSink sink =
        analyze(
            Map.of(
                "src/main/java/m/Host.java",
                """
                package m;

                public class Host {
                  int m() {
                    class Local {
                      static int counter;

                      static {
                        if (counter > 0) {
                          counter = 0;
                        }
                      }

                      int get() {
                        return counter > 0 ? 1 : 0;
                      }
                    }
                    return new Local().get();
                  }
                }
                """));

    assertThat(sink.metric("type:m.Host").complexity()).isEqualTo(new Complexity(3, 2, 2, 2));
    assertThat(sink.metric("method:m.Host#get()").complexity())
        .isEqualTo(new Complexity(2, 2, 2, 1));
    assertMetricsAreTheElements(sink);
  }

  @Test
  void umaUnidadeCujaMetricaLancaPerdeElementosDependenciasEMetricas() throws Exception {
    final Path repository = Files.createDirectories(temp.toRealPath().resolve("crash"));
    final String first = "src/p/A.java";
    final String second = "src/p/B.java";
    final String source =
        "package p;\n\npublic class A {\n  int f(boolean b) {\n    return b ? 1 : 0;"
            + "\n  }\n}\n";
    TestRepositories.write(repository, first, source);
    TestRepositories.write(
        repository, second, "package p;\n\npublic class B extends A {\n  void g() {}\n}\n");
    final SourceRoot root =
        new SourceRoot(SourceRootId.derive("src"), "src", SourceRootKind.MAIN, 2);
    final List<SourceFile> files =
        Stream.of(first, second)
            .map(
                path ->
                    new SourceFile(
                        path,
                        root.id(),
                        SourceFileStatus.ANALYZED,
                        null,
                        new SourceFile.Declarations(
                            "p",
                            List.of(path.substring(path.lastIndexOf('/') + 1, path.length() - 5)))))
            .toList();
    final Launcher launcher =
        SpoonLaunchers.configured(
            Files.createDirectories(temp.resolve("empty-classpath")), SourceTrees.NEVER);
    for (final SourceFile file : files) {
      launcher.addInputResource(repository.resolve(file.relativePath()).toString());
    }
    launcher.buildModel();
    final ModeledBatch batch =
        new ModeledBatch(
            root,
            List.of(first, second),
            launcher.getFactory(),
            repository,
            files,
            SimpleNameIndex.of(files),
            SourceTrees.NEVER,
            Map.of(first, source.getBytes(StandardCharsets.UTF_8)));
    final RecordingStructureSink sink = new RecordingStructureSink();
    final StructureExtractor extractor = new StructureExtractor(List.of(root), sink);

    extractor.begin(files);
    extractor.visit(batch);

    assertThat(batch.extractionFailures()).containsExactly(second);
    final BatchStructure delivered = sink.accepted().getFirst();
    assertThat(delivered.elements().types()).extracting(TypeElement::filePath).containsOnly(first);
    assertThat(delivered.elements().methods())
        .extracting(MethodElement::filePath)
        .containsOnly(first);
    assertThat(sink.dependencies()).as("B extends A ia com B").isEmpty();
    assertThat(sink.metric("method:p.A#f(boolean)").complexity())
        .isEqualTo(new Complexity(2, 2, 2, 1));
    assertMetricsAreTheElements(sink);
  }

  @Test
  void umaCadeiaDeVinteMilAndNaoEstouraUmaPilhaDe64KiB() throws Exception {
    final CodeFactory code = new Launcher().getFactory().Code();
    CtExpression<Boolean> chain = code.createLiteral(true);
    for (int index = 0; index < DEEP_CHAIN; index++) {
      chain = code.createBinaryOperator(chain, code.createLiteral(false), BinaryOperatorKind.AND);
    }
    final CtExpression<Boolean> deep = chain;
    final FutureTask<Integer> walk =
        new FutureTask<>(() -> CyclomaticComplexity.decisionPoints(deep));

    new Thread(null, walk, "cc", SMALL_STACK_BYTES).start();

    assertThat(walk.get()).isEqualTo(DEEP_CHAIN);
  }

  private static void assertMetricsAreTheElements(RecordingStructureSink sink) {
    for (final BatchStructure batch : sink.accepted()) {
      final List<CodeElementId> elements = new ArrayList<>();
      batch.elements().types().forEach(type -> elements.add(type.id()));
      batch.elements().methods().forEach(method -> elements.add(method.id()));
      assertThat(batch.metrics())
          .extracting(CodeMetric::element)
          .containsExactlyInAnyOrderElementsOf(elements);
    }
  }

  private static String describe(UnitExtraction.ExecutableSite site) {
    assertThat(site.owner().value()).isEqualTo("type:m.Worker");
    return ElementIds.lines(site.executable()).start()
        + " "
        + (site.method() == null ? "-" : site.method().value());
  }

  private static String describe(UnitExtraction.TypeSite site) {
    final String simpleName = site.type().isAnonymous() ? "" : site.type().getSimpleName();
    return simpleName + " " + site.owner().value() + (site.element() ? " element" : "");
  }

  private static CodeMetric metric(String id, int loc, Complexity complexity) {
    return new CodeMetric(new CodeElementId(id), loc, complexity);
  }

  private RecordingStructureSink analyze(Map<String, String> files) throws Exception {
    return analyze(temp.resolve("repository"), files);
  }

  private static RecordingStructureSink analyze(Path directory, Map<String, String> files)
      throws Exception {
    return analyze(SourceTrees.repository(directory, files));
  }

  private static RecordingStructureSink analyze(ValidatedRepository repository) {
    final List<SourceRoot> roots = repository.layout().sourceRoots();
    final RecordingStructureSink sink = new RecordingStructureSink();
    new SpoonModelBuilder()
        .analyze(repository, roots, new StructureExtractor(roots, sink), SourceTrees.NEVER);
    return sink;
  }
}
