package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.factory.FactoryImpl;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.DefaultCoreFactory;
import spoon.support.compiler.VirtualFile;

final class ModelLossPreventionTest {

  private static final String MODE =
      """
      package q;

      enum Mode {
        NONE,
        ONE
      }
      """;

  private static final String PRINTER =
      """
      package q;

      import static q.Mode.NONE;

      class Printer {
        void details(Item item) {
          item.each(value -> detail(NONE));
        }

        private void detail(Mode mode) {}
      }
      """;

  private static final String MODE_INTACT =
      "q.Mode CtEnumImpl valid=true fields=[NONE, ONE] methods=[]";

  private static final String MODE_LOST = "q.Mode CtClassImpl valid=false fields=[] methods=[]";

  private static final String PRINTER_SHAPE =
      "q.Printer CtClassImpl valid=true fields=[] methods=[detail, details]";

  @TempDir private Path temp;

  @Test
  void oGatilhoMinimoDaFase23NaoTrocaOEnumPorUmaClasseVazia() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.of("src/main/java/q/Mode.java", MODE, "src/main/java/q/Printer.java", PRINTER));

    assertThat(unguardedShapes(repository)).containsExactly(MODE_LOST, PRINTER_SHAPE);

    final Run run = analyze(repository);
    assertIntact(run, "src/main/java/q/Mode.java", "src/main/java/q/Printer.java");
    assertThat(run.shapes()).containsExactly(MODE_INTACT, PRINTER_SHAPE);
    assertThat(run.typeKinds()).containsExactly("type:q.Mode ENUM", "type:q.Printer CLASS");
  }

  @Test
  void comUmGatilhoAntesEOutroDepoisDoArquivoQueDeclaraOEnumEleChegaInteiro() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.of(
                "src/main/java/q/APrinter.java",
                PRINTER.replace("class Printer", "class APrinter"),
                "src/main/java/q/Mode.java",
                MODE,
                "src/main/java/q/Printer.java",
                PRINTER));
    final String aPrinter = "q.APrinter CtClassImpl valid=true fields=[] methods=[detail, details]";

    assertThat(unguardedShapes(repository)).containsExactly(aPrinter, MODE_LOST, PRINTER_SHAPE);

    final Run run = analyze(repository);
    assertIntact(
        run,
        "src/main/java/q/APrinter.java",
        "src/main/java/q/Mode.java",
        "src/main/java/q/Printer.java");
    assertThat(run.shapes()).containsExactly(aPrinter, MODE_INTACT, PRINTER_SHAPE);
    assertThat(run.typeKinds())
        .containsExactly("type:q.APrinter CLASS", "type:q.Mode ENUM", "type:q.Printer CLASS");
  }

  @Test
  void umaInterfaceDeConstantesContinuaInterface() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.of(
                "src/main/java/q/Keys.java",
                """
                package q;

                interface Keys {
                  String KEY = "k";
                  String OTHER = "o";
                }
                """,
                "src/main/java/q/User.java",
                """
                package q;

                import static q.Keys.KEY;

                class User {
                  void details(Item item) {
                    item.each(value -> use(KEY));
                  }

                  private void use(String key) {}
                }
                """));
    final String user = "q.User CtClassImpl valid=true fields=[] methods=[details, use]";

    assertThat(unguardedShapes(repository))
        .containsExactly("q.Keys CtClassImpl valid=false fields=[] methods=[]", user);

    final Run run = analyze(repository);
    assertIntact(run, "src/main/java/q/Keys.java", "src/main/java/q/User.java");
    assertThat(run.shapes())
        .containsExactly("q.Keys CtInterfaceImpl valid=true fields=[KEY, OTHER] methods=[]", user);
    assertThat(run.typeKinds()).containsExactly("type:q.Keys INTERFACE", "type:q.User CLASS");
  }

  @Test
  void oLambdaPassadoAUmMetodoEstaticoDeUmTipoAusenteNaoTrocaOEnum() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.of(
                "src/main/java/q/Mode.java",
                MODE,
                "src/main/java/q/Printer.java",
                """
                package q;

                import static q.Mode.NONE;

                import other.Listener;

                class Printer {
                  Object details() {
                    return Listener.wrap(value -> detail(NONE));
                  }

                  private void detail(Mode mode) {}
                }
                """));

    assertThat(unguardedShapes(repository)).containsExactly(MODE_LOST, PRINTER_SHAPE);

    final Run run = analyze(repository);
    assertIntact(run, "src/main/java/q/Mode.java", "src/main/java/q/Printer.java");
    assertThat(run.shapes()).containsExactly(MODE_INTACT, PRINTER_SHAPE);
    assertThat(run.typeKinds()).containsExactly("type:q.Mode ENUM", "type:q.Printer CLASS");
  }

  @Test
  void oLambdaPassadoAUmMetodoResolvidoComParametroDeTipoAusenteNaoTrocaOEnum() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.of(
                "src/main/java/q/Mode.java",
                MODE,
                "src/main/java/q/Printer.java",
                """
                package q;

                import static q.Mode.NONE;

                class Printer {
                  void details() {
                    Runner.run(value -> detail(NONE));
                  }

                  private void detail(Mode mode) {}
                }
                """,
                "src/main/java/q/Runner.java",
                """
                package q;

                import other.Callback;

                class Runner {
                  static void run(Callback callback) {}
                }
                """));
    final String runner = "q.Runner CtClassImpl valid=true fields=[] methods=[run]";

    assertThat(unguardedShapes(repository)).containsExactly(MODE_LOST, PRINTER_SHAPE, runner);

    final Run run = analyze(repository);
    assertIntact(
        run,
        "src/main/java/q/Mode.java",
        "src/main/java/q/Printer.java",
        "src/main/java/q/Runner.java");
    assertThat(run.shapes()).containsExactly(MODE_INTACT, PRINTER_SHAPE, runner);
    assertThat(run.typeKinds())
        .containsExactly("type:q.Mode ENUM", "type:q.Printer CLASS", "type:q.Runner CLASS");
  }

  @Test
  void semGatilhoOModeloEIgualAoDoSpoonSemAGuarda() throws Exception {
    final ValidatedRepository repository =
        repository(
            Map.ofEntries(
                Map.entry(
                    "src/main/java/a/Mode.java",
                    "package a;\n\nclass Mode {\n  static final Mode None = new Mode();\n}\n"),
                Map.entry(
                    "src/main/java/a/Printer.java",
                    lambdaUser(
                        "a", "Printer", "import static a.Mode.None;", "detail(None)", "Mode")),
                Map.entry("src/main/java/b/Mode.java", MODE.replace("package q;", "package b;")),
                Map.entry(
                    "src/main/java/b/Printer.java",
                    lambdaUser("b", "Printer", "import static b.Mode.*;", "detail(NONE)", "Mode")),
                Map.entry("src/main/java/c/Mode.java", MODE.replace("package q;", "package c;")),
                Map.entry(
                    "src/main/java/c/Printer.java",
                    "package c;\n\nimport static c.Mode.NONE;\n\n"
                        + "class Printer {\n  void details(Item item) {\n    item.each(NONE);\n  }\n}\n"),
                Map.entry("src/main/java/d/Mode.java", MODE.replace("package q;", "package d;")),
                Map.entry(
                    "src/main/java/d/Printer.java",
                    "package d;\n\nimport static d.Mode.NONE;\n\nimport java.util.List;\n\n"
                        + "class Printer {\n  void details(List<String> items) {\n"
                        + "    items.forEach(value -> detail(NONE));\n  }\n\n"
                        + "  private void detail(Mode mode) {}\n}\n"),
                Map.entry(
                    "src/main/java/e/Outer.java",
                    "package e;\n\nclass Outer {\n  static class Inner {\n"
                        + "    static final int MAX = 1;\n  }\n}\n"),
                Map.entry(
                    "src/main/java/e/User.java",
                    lambdaUser(
                        "e", "User", "import static e.Outer.Inner.MAX;", "detail(MAX)", "int")),
                Map.entry(
                    "src/main/java/f/Printer.java",
                    lambdaUser(
                        "f", "Printer", "import static f.Mode.NONE;", "detail(NONE)", "Mode"))));
    final String printer = " CtClassImpl valid=true fields=[] methods=[detail, details]";
    final List<String> expected =
        List.of(
            "Inner CtClassImpl valid=false fields=[] methods=[]",
            "a.Mode CtClassImpl valid=true fields=[None] methods=[]",
            "a.Printer" + printer,
            "b.Mode CtEnumImpl valid=true fields=[NONE, ONE] methods=[]",
            "b.Printer" + printer,
            "c.Mode CtEnumImpl valid=true fields=[NONE, ONE] methods=[]",
            "c.Printer CtClassImpl valid=true fields=[] methods=[details]",
            "d.Mode CtEnumImpl valid=true fields=[NONE, ONE] methods=[]",
            "d.Printer" + printer,
            "e.Outer CtClassImpl valid=true fields=[] methods=[]",
            "e.Outer$Inner CtClassImpl valid=true fields=[MAX] methods=[]",
            "e.User" + printer,
            "f.Mode CtClassImpl valid=false fields=[] methods=[]",
            "f.Printer" + printer);

    assertThat(unguardedShapes(repository)).containsExactlyElementsOf(expected);

    final Run run = analyze(repository);
    assertThat(run.analysis().parseFailures()).isZero();
    assertThat(run.analysis().diagnostics().batches()).isEqualTo(1);
    assertThat(run.shapes()).containsExactlyElementsOf(expected);
  }

  @Test
  void aGuardaSoDesligaAClasseQuandoOPacoteJaTemUmTipoEscritoComAqueleNome() throws Exception {
    final Factory factory = built(MODE);
    final CtPackage q = factory.Package().get("q");
    final CtType<?> mode = q.getType("Mode");
    assertThat(mode.getPosition().isValidPosition()).as("pré-condição").isTrue();

    final CtClass<?> detached = factory.Class().create("q.Mode");
    assertThat(typeIn(q, "Mode")).isSameAs(mode);
    assertThat(q.getTypes()).containsExactly(mode);
    assertThat(detached.getQualifiedName()).isEqualTo("q.Mode");
    assertThat(detached.getParent()).isSameAs(q);

    final CtClass<?> inner = factory.Class().create("q.Mode$Inner");
    assertThat(typeIn(q, "Mode")).isSameAs(mode);
    assertThat(mode.getNestedTypes()).isEmpty();
    assertThat(inner.getQualifiedName()).isEqualTo("q.Mode$Inner");

    final CtClass<?> absent = factory.Class().create("q.Absent");
    assertThat(typeIn(q, "Absent")).isSameAs(absent);
    final CtClass<?> again = factory.Class().create(q, "Absent");
    assertThat(typeIn(q, "Absent")).isSameAs(again).isNotSameAs(absent);
  }

  @Test
  void umTipoSombraNaoEProtegidoMesmoComPosicao() throws Exception {
    final Factory factory = built(MODE);
    final CtPackage q = factory.Package().get("q");
    final CtType<?> mode = q.getType("Mode");
    mode.setShadow(true);

    final CtClass<?> replacement = factory.Class().create(q, "Mode");

    assertThat(typeIn(q, "Mode")).isSameAs(replacement);
  }

  private static CtType<?> typeIn(CtPackage pkg, String simpleName) {
    return pkg.getType(simpleName);
  }

  private static String lambdaUser(
      String pkg, String name, String staticImport, String use, String type) {
    return "package "
        + pkg
        + ";\n\n"
        + staticImport
        + "\n\nclass "
        + name
        + " {\n  void details(Item item) {\n    item.each(value -> "
        + use
        + ");\n  }\n\n  private void detail("
        + type
        + " value) {}\n}\n";
  }

  private ValidatedRepository repository(Map<String, String> files) throws Exception {
    return SourceTrees.repository(temp.resolve("repository"), files);
  }

  private Factory built(String source) throws Exception {
    final Launcher launcher = SpoonLaunchers.configured(emptyClasspath(), SourceTrees.NEVER);
    launcher.addInputResource(new VirtualFile(source, "q/Mode.java"));
    launcher.buildModel();
    return launcher.getFactory();
  }

  private static Run analyze(ValidatedRepository repository) {
    final List<SourceRoot> roots = repository.layout().sourceRoots();
    final RecordingElementSink sink = new RecordingElementSink();
    final CodeElementExtractor extractor = new CodeElementExtractor(roots, sink);
    final List<String> shapes = new ArrayList<>();
    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(
                repository,
                roots,
                new BatchVisitor() {
                  @Override
                  public void begin(List<SourceFile> files) {
                    extractor.begin(files);
                  }

                  @Override
                  public void visit(ModeledBatch batch) {
                    shapes.addAll(shapes(batch.model()));
                    extractor.visit(batch);
                  }

                  @Override
                  public void end(SourceAnalysis finished) {
                    extractor.end(finished);
                  }
                },
                SourceTrees.NEVER);
    return new Run(analysis, extractor.issues(), sink, List.copyOf(shapes));
  }

  private List<String> unguardedShapes(ValidatedRepository repository) throws Exception {
    final Launcher configured = SpoonLaunchers.configured(emptyClasspath(), SourceTrees.NEVER);
    final Factory plain = new FactoryImpl(new DefaultCoreFactory(), configured.getEnvironment());
    final spoon.SpoonModelBuilder compiler = configured.createCompiler(plain);
    for (final SourceRoot root : repository.layout().sourceRoots()) {
      compiler.addInputSource(repository.snapshot().root().resolve(root.relativePath()).toFile());
    }
    compiler.build();
    return shapes(plain.getModel());
  }

  private Path emptyClasspath() throws Exception {
    return Files.createDirectories(temp.resolve("empty-classpath"));
  }

  private static void assertIntact(Run run, String... files) {
    assertThat(run.analysis().files())
        .extracting(file -> file.relativePath() + " " + file.status())
        .containsExactlyElementsOf(Arrays.stream(files).map(file -> file + " ANALYZED").toList());
    assertThat(run.analysis().issues()).isEmpty();
    assertThat(run.issues()).isEmpty();
    assertThat(run.analysis().diagnostics().batches()).as("um modelo só").isEqualTo(1);
  }

  private static List<String> shapes(CtModel model) {
    return model.getAllTypes().stream()
        .filter(type -> !type.isShadow())
        .flatMap(type -> type.getElements(new TypeFilter<CtType<?>>(CtType.class)).stream())
        .filter(type -> !(type instanceof CtTypeParameter))
        .map(ModelLossPreventionTest::shape)
        .sorted()
        .toList();
  }

  private static String shape(CtType<?> type) {
    return type.getQualifiedName()
        + " "
        + type.getClass().getSimpleName()
        + " valid="
        + type.getPosition().isValidPosition()
        + " fields="
        + written(type.getFields())
        + " methods="
        + written(type.getMethods());
  }

  private static List<String> written(Collection<? extends CtNamedElement> members) {
    return members.stream()
        .filter(member -> !member.isImplicit())
        .map(CtNamedElement::getSimpleName)
        .sorted()
        .toList();
  }

  private record Run(
      SourceAnalysis analysis,
      List<SourceIssue> issues,
      RecordingElementSink sink,
      List<String> shapes) {

    List<String> typeKinds() {
      return sink.types().stream().map(type -> type.id().value() + " " + type.typeKind()).toList();
    }
  }
}
