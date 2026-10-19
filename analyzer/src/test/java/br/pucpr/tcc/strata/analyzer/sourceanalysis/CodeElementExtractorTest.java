package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingElementSink.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Modifier;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

final class CodeElementExtractorTest {

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  @TempDir private Path temp;

  @Test
  void simplePackagesReproduzOGoldenInteiroEmOrdemDePontoDeCodigo() throws Exception {
    final Extraction run =
        extract(SourceTrees.fixture("simple-packages", temp.toRealPath().resolve("fixture")));

    assertThat(ids(run.sink().packages()))
        .containsExactly("pkg:", "pkg:br.pucpr", "pkg:br.pucpr.orders");
    assertThat(run.sink().types())
        .extracting(type -> type.id().value() + " " + type.typeKind())
        .containsExactly(
            "type:Bootstrap CLASS",
            "type:br.pucpr.Log CLASS",
            "type:br.pucpr.orders.Order CLASS",
            "type:br.pucpr.orders.Order$Builder CLASS",
            "type:br.pucpr.orders.OrderService CLASS");
    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:Bootstrap#<init>()",
            "method:Bootstrap#main(java.lang.String[])",
            "method:br.pucpr.Log#<init>()",
            "method:br.pucpr.Log#info(java.lang.String,java.lang.Object[])",
            "method:br.pucpr.orders.Order#<init>(java.util.UUID)",
            "method:br.pucpr.orders.Order#id()",
            "method:br.pucpr.orders.Order$Builder#<init>()",
            "method:br.pucpr.orders.Order$Builder#build()",
            "method:br.pucpr.orders.Order$Builder#id(java.util.UUID)",
            "method:br.pucpr.orders.OrderService#<init>(java.util.List)",
            "method:br.pucpr.orders.OrderService#auditor()",
            "method:br.pucpr.orders.OrderService#build(java.util.UUID)",
            "method:br.pucpr.orders.OrderService#first(br.pucpr.orders.Order)",
            "method:br.pucpr.orders.OrderService#register(java.util.List)",
            "method:br.pucpr.orders.OrderService#run()");
    assertThat(ids(run.sink().fileElements()))
        .containsExactly(
            "file:src/main/java/Bootstrap.java",
            "file:src/main/java/br/pucpr/Log.java",
            "file:src/main/java/br/pucpr/orders/Order.java",
            "file:src/main/java/br/pucpr/orders/OrderService.java",
            "file:src/main/java/br/pucpr/orders/package-info.java");
    assertThat(run.issues()).isEmpty();
  }

  @Test
  void simplePackagesDaOsAtributosDeCadaColuna() throws Exception {
    final Extraction run =
        extract(SourceTrees.fixture("simple-packages", temp.toRealPath().resolve("fixture")));
    final TypeElement builder = run.sink().type("type:br.pucpr.orders.Order$Builder");
    final MethodElement build = run.sink().method("method:br.pucpr.orders.Order$Builder#build()");

    assertThat(run.sink().begun()).hasSize(5);
    assertThat(run.sink().ended()).isEqualTo(run.analysis().files());
    assertThat(run.sink().ends()).isEqualTo(1);
    assertThat(builder.simpleName()).isEqualTo("Builder");
    assertThat(builder.qualifiedName()).isEqualTo("br.pucpr.orders.Order.Builder");
    assertThat(builder.packageId().value()).isEqualTo("pkg:br.pucpr.orders");
    assertThat(builder.filePath()).isEqualTo("src/main/java/br/pucpr/orders/Order.java");
    assertThat(builder.rootId()).isEqualTo(SourceRootId.derive("src/main/java"));
    assertThat(canonical(builder.modifiers()))
        .containsExactly(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
    assertThat(builder.lines()).isEqualTo(new SourceLines(22, 37));
    assertThat(builder.isTest()).isFalse();
    assertThat(build.simpleName()).isEqualTo("build");
    assertThat(build.qualifiedName()).isEqualTo("br.pucpr.orders.Order.Builder#build");
    assertThat(build.declaringTypeId()).isEqualTo(builder.id());
    assertThat(build.lines()).isEqualTo(new SourceLines(34, 36));
    assertThat(run.sink().packages())
        .extracting(pkg -> pkg.simpleName() + "|" + pkg.qualifiedName())
        .containsExactly("|", "pucpr|br.pucpr", "orders|br.pucpr.orders");
    assertThat(run.sink().file("file:src/main/java/br/pucpr/orders/package-info.java"))
        .satisfies(
            file -> {
              assertThat(file.packageId().value()).isEqualTo("pkg:br.pucpr.orders");
              assertThat(file.simpleName()).isEqualTo("package-info.java");
              assertThat(file.qualifiedName())
                  .isEqualTo("src/main/java/br/pucpr/orders/package-info.java");
            });
    assertThat(run.sink().file("file:src/main/java/Bootstrap.java").packageId().value())
        .isEqualTo("pkg:");
  }

  @Test
  void anonimaLocalNomeadaNaAnonimaECorpoDeConstanteNaoSaoElementos() throws Exception {
    final Extraction run = extract(Map.of("src/main/java/n/Outer.java", NESTING));

    assertThat(run.sink().types())
        .extracting(type -> type.id().value() + " " + type.typeKind())
        .containsExactly(
            "type:n.Outer CLASS", "type:n.Outer$Member CLASS", "type:n.Outer$Mode ENUM");
    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:n.Outer#<clinit>()",
            "method:n.Outer#<init>()",
            "method:n.Outer#anonWithCtorArgs()",
            "method:n.Outer#lm()",
            "method:n.Outer#locals()",
            "method:n.Outer#namedInAnon()",
            "method:n.Outer#run()",
            "method:n.Outer#start()",
            "method:n.Outer#x()",
            "method:n.Outer$Member#<init>()",
            "method:n.Outer$Member#run()",
            "method:n.Outer$Mode#<init>()",
            "method:n.Outer$Mode#g()",
            "method:n.Outer$Mode#onlyInBody()");
    assertThat(run.sink().packages()).extracting(pkg -> pkg.id().value()).containsExactly("pkg:n");
    assertThat(run.issues()).isEmpty();
  }

  @Test
  void oMesmoIdNoMesmoTipoEUmElementoComADeclaracaoDoProprioDono() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Owner.java",
                """
                package p;

                public class Owner {
                  Runnable early =
                      new Runnable() {
                        public void run() {}
                      };

                  public synchronized void run() {}

                  void twice() {
                    new Runnable() {
                      public void run() {}
                    };
                  }
                }
                """,
                "src/main/java/p/Lent.java",
                """
                package p;

                public class Lent {
                  Runnable first =
                      new Runnable() {
                        public void run() {}
                      };

                  Runnable second =
                      new Runnable() {
                        protected void run() {}
                      };
                }
                """));

    final MethodElement own = run.sink().method("method:p.Owner#run()");
    final MethodElement lent = run.sink().method("method:p.Lent#run()");
    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:p.Lent#<init>()",
            "method:p.Lent#run()",
            "method:p.Owner#<init>()",
            "method:p.Owner#run()",
            "method:p.Owner#twice()");
    assertThat(own.lines()).isEqualTo(new SourceLines(9, 9));
    assertThat(canonical(own.modifiers())).containsExactly(Modifier.PUBLIC, Modifier.SYNCHRONIZED);
    assertThat(lent.lines()).isEqualTo(new SourceLines(6, 6));
    assertThat(canonical(lent.modifiers())).containsExactly(Modifier.PUBLIC);
  }

  @Test
  void membrosImplicitosDeTiposElementoFicamSemLinhas() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/c/Point.java",
                """
                package c;

                public record Point(int x, String label) {
                  static int count;

                  static {
                    count = 1;
                  }

                  static {
                    count = 2;
                  }

                  public static Point origin() {
                    Runnable r = () -> {};
                    return new Point(0, "o");
                  }
                }
                """,
                "src/main/java/c/Plain.java",
                """
                package c;

                public class Plain {
                  int a;

                  {
                    a = 1;
                  }

                  void m() {
                    Runnable r = () -> {};
                  }
                }
                """,
                "src/main/java/c/Color.java",
                """
                package c;

                public enum Color {
                  RED,
                  GREEN
                }
                """));

    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:c.Color#<init>()",
            "method:c.Plain#<init>()",
            "method:c.Plain#m()",
            "method:c.Point#<clinit>()",
            "method:c.Point#<init>(int,java.lang.String)",
            "method:c.Point#label()",
            "method:c.Point#origin()",
            "method:c.Point#x()");
    assertThat(run.sink().types())
        .extracting(type -> type.id().value() + " " + type.typeKind())
        .containsExactly("type:c.Color ENUM", "type:c.Plain CLASS", "type:c.Point RECORD");
    for (final String implicit :
        List.of(
            "method:c.Color#<init>()",
            "method:c.Plain#<init>()",
            "method:c.Point#<init>(int,java.lang.String)",
            "method:c.Point#label()",
            "method:c.Point#x()")) {
      assertThat(run.sink().method(implicit).lines())
          .as(implicit)
          .isEqualTo(SourceLines.UNAVAILABLE);
    }
    assertThat(run.sink().method("method:c.Point#<clinit>()").lines())
        .isEqualTo(new SourceLines(6, 8));
  }

  @Test
  void packageInfoEModuleInfoNaoDaoTipoNemPacote() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/module-info.java", "module m.app {}\n",
                "src/main/java/p/package-info.java", "/** Documentado. */\npackage p;\n",
                "src/main/java/p/A.java", "package p;\n\npublic class A {}\n",
                "src/main/java/q/package-info.java", "/** Só documentação. */\npackage q;\n"));

    assertThat(ids(run.sink().packages())).containsExactly("pkg:p");
    assertThat(ids(run.sink().types())).containsExactly("type:p.A");
    assertThat(packageOfEachFile(run))
        .containsExactly(
            Map.entry("file:src/main/java/module-info.java", "null"),
            Map.entry("file:src/main/java/p/A.java", "pkg:p"),
            Map.entry("file:src/main/java/p/package-info.java", "pkg:p"),
            Map.entry("file:src/main/java/q/package-info.java", "null"));
  }

  @Test
  void oPacotePadraoEPkgSozinho() throws Exception {
    final Extraction run =
        extract(Map.of("src/main/java/Root.java", "public class Root {\n  void m() {}\n}\n"));

    assertThat(ids(run.sink().packages())).containsExactly("pkg:");
    assertThat(run.sink().types())
        .singleElement()
        .satisfies(
            root -> {
              assertThat(root.id().value()).isEqualTo("type:Root");
              assertThat(root.qualifiedName()).isEqualTo("Root");
              assertThat(root.packageId().value()).isEqualTo("pkg:");
            });
    assertThat(ids(run.sink().methods()))
        .containsExactly("method:Root#<init>()", "method:Root#m()");
  }

  @Test
  void umFqnRepetidoEmDuasRaizesFicaComOPrimeiroEAvisaUmaVez() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "mod1/src/main/java/a/Y.java",
                    "package a;\n\npublic class Y {\n  void one() {}\n}\n",
                "mod2/src/main/java/a/Y.java",
                    "package a;\n\npublic class Y {\n  void two() {}\n}\n"));

    assertThat(run.roots()).extracting(SourceRoot::relativePath).hasSize(2);
    assertThat(ids(run.sink().types())).containsExactly("type:a.Y");
    assertThat(run.sink().type("type:a.Y").filePath()).isEqualTo("mod1/src/main/java/a/Y.java");
    assertThat(ids(run.sink().methods()))
        .containsExactly("method:a.Y#<init>()", "method:a.Y#one()");
    assertThat(run.issues())
        .containsExactly(
            SourceIssue.duplicateElement(
                "mod2/src/main/java/a/Y.java", "mod1/src/main/java/a/Y.java"));
    assertThat(ids(run.sink().fileElements()))
        .containsExactly("file:mod1/src/main/java/a/Y.java", "file:mod2/src/main/java/a/Y.java");
  }

  @Test
  void umFqnRepetidoNaMesmaRaizFicaComOPrimeiroLoteVisitado() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/a/X.java", "package a;\n\npublic class X {\n  void one() {}\n}\n",
                "src/main/java/a/X2.java", "package a;\n\nclass X {\n  void two() {}\n}\n"));

    assertThat(run.analysis().files())
        .extracting(SourceFile::status)
        .containsOnly(SourceFileStatus.ANALYZED);
    assertThat(run.analysis().diagnostics().builds()).isGreaterThan(1);
    final TypeElement kept = run.sink().types().getFirst();
    assertThat(ids(run.sink().types())).containsExactly("type:a.X");
    assertThat(run.issues())
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.code()).isEqualTo(SourceIssue.Code.SOURCE_DUPLICATE_ELEMENT);
              assertThat(
                      Set.of(issue.params().get("filePath"), issue.params().get("firstFilePath")))
                  .containsExactlyInAnyOrder("src/main/java/a/X.java", "src/main/java/a/X2.java");
              assertThat(issue.params().get("firstFilePath")).isEqualTo(kept.filePath());
            });
    final String keptMethod =
        kept.filePath().equals("src/main/java/a/X.java") ? "method:a.X#one()" : "method:a.X#two()";
    assertThat(ids(run.sink().methods())).containsExactly("method:a.X#<init>()", keptMethod);
  }

  @Test
  void umTipoDeTopoComDolarEUmAninhadoColidemEOPrimeiroCaminhoFica() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/g/Dollar.java",
                "package g;\n\nclass Foo$Bar {\n  void top() {}\n}\n",
                "src/main/java/g/Foo.java",
                """
                package g;

                public class Foo {
                  static class Bar {
                    void nested() {}
                  }

                  void own() {}
                }
                """));

    assertThat(ids(run.sink().types())).containsExactly("type:g.Foo", "type:g.Foo$Bar");
    assertThat(run.sink().type("type:g.Foo$Bar").filePath())
        .isEqualTo("src/main/java/g/Dollar.java");
    assertThat(run.sink().type("type:g.Foo$Bar").qualifiedName()).isEqualTo("g.Foo$Bar");
    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:g.Foo#<init>()",
            "method:g.Foo#own()",
            "method:g.Foo$Bar#<init>()",
            "method:g.Foo$Bar#top()");
    assertThat(run.issues())
        .containsExactly(
            SourceIssue.duplicateElement(
                "src/main/java/g/Foo.java", "src/main/java/g/Dollar.java"));
  }

  @Test
  void umIdDeMetodoAcimaDoTetoSaiComUmAvisoPorArquivo() throws Exception {
    final String longName = "L".repeat(300);
    final String parameters =
        IntStream.range(0, 14)
            .mapToObj(index -> longName + " a" + index)
            .collect(Collectors.joining(", "));
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Wide.java",
                "package p;\n\npublic class Wide {\n  void ok() {}\n\n  void first("
                    + parameters
                    + ") {}\n\n  void second("
                    + parameters
                    + ") {}\n}\n\nclass "
                    + longName
                    + " {}\n"));

    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:p." + longName + "#<init>()", "method:p.Wide#<init>()", "method:p.Wide#ok()");
    assertThat(ids(run.sink().types())).containsExactly("type:p." + longName, "type:p.Wide");
    assertThat(run.issues())
        .containsExactly(
            SourceIssue.elementIdTooLong(
                "src/main/java/p/Wide.java", AnalysisConstants.MAX_ELEMENT_ID_BYTES));
  }

  @Test
  void osModificadoresSaoSoOsEscritos() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/m/Iface.java",
                """
                package m;

                public interface Iface {
                  void abs();

                  default void def() {}

                  static void st() {}

                  private void priv() {}
                }
                """,
                "src/main/java/m/Ordered.java",
                """
                package m;

                final public class Ordered {
                  static protected synchronized void s() {}
                }
                """,
                "src/main/java/m/Mood.java",
                """
                package m;

                public enum Mood {
                  HAPPY {
                    void f() {}
                  };

                  void f() {}
                }
                """));

    assertThat(canonical(run.sink().type("type:m.Iface").modifiers()))
        .containsExactly(Modifier.PUBLIC);
    assertThat(canonical(run.sink().method("method:m.Iface#abs()").modifiers())).isEmpty();
    assertThat(canonical(run.sink().method("method:m.Iface#def()").modifiers()))
        .containsExactly(Modifier.DEFAULT);
    assertThat(canonical(run.sink().method("method:m.Iface#st()").modifiers()))
        .containsExactly(Modifier.STATIC);
    assertThat(canonical(run.sink().method("method:m.Iface#priv()").modifiers()))
        .containsExactly(Modifier.PRIVATE);
    assertThat(canonical(run.sink().type("type:m.Ordered").modifiers()))
        .containsExactly(Modifier.PUBLIC, Modifier.FINAL);
    assertThat(canonical(run.sink().method("method:m.Ordered#s()").modifiers()))
        .containsExactly(Modifier.PROTECTED, Modifier.STATIC, Modifier.SYNCHRONIZED);
    assertThat(canonical(run.sink().type("type:m.Mood").modifiers()))
        .containsExactly(Modifier.PUBLIC);
    assertThat(run.sink().type("type:m.Iface").typeKind()).isEqualTo(TypeKind.INTERFACE);
  }

  @Test
  void aPrimeiraLinhaEADaPrimeiraAnotacaoOuModificadorNuncaADoJavadoc() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Lined.java",
                """
                package p;

                /** Documentado. */
                @Deprecated
                public final class Lined {

                  /** Documentado. */
                  @SuppressWarnings("unused")
                  public static void annotated() {
                  }

                  /** Documentado. */
                  void bare() {
                  }

                  <T>
                  T generic() {
                    return null;
                  }
                }
                """,
                "src/main/java/p/Note.java",
                """
                package p;

                public @interface Note {
                  String value() default "";
                }
                """));

    assertThat(run.sink().type("type:p.Lined").lines()).isEqualTo(new SourceLines(4, 20));
    assertThat(run.sink().method("method:p.Lined#annotated()").lines())
        .isEqualTo(new SourceLines(8, 10));
    assertThat(run.sink().method("method:p.Lined#bare()").lines())
        .isEqualTo(new SourceLines(13, 14));
    assertThat(run.sink().method("method:p.Lined#generic()").lines())
        .isEqualTo(new SourceLines(16, 19));
    assertThat(run.sink().type("type:p.Note").typeKind()).isEqualTo(TypeKind.ANNOTATION);
    assertThat(run.sink().method("method:p.Note#value()").lines()).isEqualTo(new SourceLines(4, 4));
  }

  @Test
  void umTipoLocalNoParametroViraONomeSimples() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/l/Host.java",
                """
                package l;

                import java.util.List;

                public class Host {
                  void scope() {
                    class Local {
                      void self(Local other) {}

                      void list(List<Local> others) {}

                      <T extends Local> void bounded(T t) {}
                    }
                  }

                  void second() {
                    class Local {
                      void self(Local other) {}
                    }
                  }
                }
                """));

    assertThat(ids(run.sink().methods()))
        .containsExactly(
            "method:l.Host#<init>()",
            "method:l.Host#bounded(Local)",
            "method:l.Host#list(java.util.List)",
            "method:l.Host#scope()",
            "method:l.Host#second()",
            "method:l.Host#self(Local)");
  }

  @Test
  void regra7OsMesmosIdsComUmaRaizOuComDuasRaizes() throws Exception {
    final Extraction oneRoot =
        extract(
            Map.of(
                "src/main/java/a/Api.java", API,
                "src/main/java/b/Helper.java", HELPER,
                "src/main/java/b/User.java", USER));
    final ValidatedRepository split =
        SourceTrees.repository(
            temp.resolve("two-roots"),
            Map.of(
                "api/src/main/java/a/Api.java", API,
                "api/src/main/java/b/Helper.java", HELPER,
                "user/src/main/java/b/User.java", USER));
    final Extraction twoRoots = extract(split);

    final List<String> expected =
        List.of(
            "method:b.User#<init>()",
            "method:b.User#array(a.Api[])",
            "method:b.User#bounded(a.Api)",
            "method:b.User#nested(a.Api$Inner)",
            "method:b.User#plain(a.Api)",
            "method:b.User#sibling(b.Helper)",
            "method:b.User#varargs(a.Api[])");
    assertThat(oneRoot.roots()).hasSize(1);
    assertThat(twoRoots.roots()).hasSize(2);
    assertThat(userMethods(oneRoot)).containsExactlyElementsOf(expected);
    assertThat(userMethods(twoRoots)).containsExactlyElementsOf(expected);
    assertThat(rawUserParameters(split))
        .containsExactly("Api", "b.Api", "Api$Inner", "Api", "Helper", "Api");
  }

  @Test
  void umaRaizDeTesteMarcaTiposMetodosEArquivos() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/A.java", "package p;\n\npublic class A {}\n",
                "src/test/java/p/ATest.java", "package p;\n\nclass ATest {\n  void t() {}\n}\n"));

    assertThat(run.sink().type("type:p.A").isTest()).isFalse();
    assertThat(run.sink().type("type:p.ATest").isTest()).isTrue();
    assertThat(run.sink().type("type:p.ATest").rootId())
        .isEqualTo(SourceRootId.derive("src/test/java"));
    assertThat(run.sink().method("method:p.ATest#t()").isTest()).isTrue();
    assertThat(run.sink().method("method:p.A#<init>()").isTest()).isFalse();
    assertThat(run.sink().file("file:src/test/java/p/ATest.java").isTest()).isTrue();
    assertThat(run.sink().file("file:src/main/java/p/A.java").isTest()).isFalse();
  }

  @Test
  void umArquivoQueFalhouOuPassouDoTetoAindaTemFileId() throws Exception {
    final Path directory = temp.resolve("outcomes");
    final String padding = "// " + "x".repeat(AnalysisConstants.MAX_SOURCE_FILE_BYTES) + "\n";
    TestRepositories.write(
        directory, "src/main/java/p/Big.java", padding + "package p;\nclass Big {}\n");
    final Extraction run =
        extract(
            directory,
            Map.of(
                "src/main/java/p/Broken.java", "package p;\nclass Broken {\n",
                "src/main/java/p/Fine.java", "package p;\n\nclass Fine {}\n"));

    assertThat(run.analysis().files())
        .extracting(file -> file.relativePath() + " " + file.status())
        .containsExactly(
            "src/main/java/p/Big.java SKIPPED_TOO_LARGE",
            "src/main/java/p/Broken.java PARSE_FAILED",
            "src/main/java/p/Fine.java ANALYZED");
    assertThat(packageOfEachFile(run))
        .containsExactly(
            Map.entry("file:src/main/java/p/Big.java", "null"),
            Map.entry("file:src/main/java/p/Broken.java", "null"),
            Map.entry("file:src/main/java/p/Fine.java", "pkg:p"));
  }

  @Test
  void umNomeDeArquivoComOverrideBidiFicaByteAByte() throws Exception {
    final String path = "src/main/java/p/Rel" + Character.toString(0x20_2E) + "gpj.java";
    final Extraction run = extract(Map.of(path, "package p;\n\nclass Rel {}\n"));

    final FileElement file = run.sink().fileElements().getFirst();
    assertThat(file.id().value().getBytes(StandardCharsets.UTF_8))
        .isEqualTo(("file:" + path).getBytes(StandardCharsets.UTF_8));
    assertThat(file.relativePath()).isEqualTo(path);
    assertThat(file.simpleName()).isEqualTo("Rel" + Character.toString(0x20_2E) + "gpj.java");
  }

  @Test
  void doisCaminhosIguaisEmNfcDaoUmFileIdSoEAvisam() {
    final String nfc = "src/p/C" + Character.toString(0xE3) + "o.java";
    final String nfd = "src/p/Ca" + Character.toString(0x03_03) + "o.java";
    final SourceRoot root =
        new SourceRoot(SourceRootId.derive("src"), "src", SourceRootKind.MAIN, 2);
    final List<SourceFile> files =
        List.of(
            new SourceFile(nfc, root.id(), SourceFileStatus.ANALYZED, null, null),
            new SourceFile(nfd, root.id(), SourceFileStatus.ANALYZED, null, null));
    final RecordingElementSink sink = new RecordingElementSink();
    final CodeElementExtractor extractor = new CodeElementExtractor(List.of(root), sink);

    extractor.begin(files);
    extractor.end(analysisOf(files));

    assertThat(sink.fileElements()).extracting(FileElement::relativePath).containsExactly(nfc);
    assertThat(sink.ended()).isEqualTo(files);
    assertThat(extractor.issues()).containsExactly(SourceIssue.duplicateElement(nfd, nfc));
  }

  @Test
  void umArquivoComBarraInvertidaNoNomeTemElementoFile() {
    final SourceRoot root =
        new SourceRoot(SourceRootId.derive("src"), "src", SourceRootKind.MAIN, 2);
    final List<SourceFile> files =
        List.of(
            new SourceFile("src/p/We\\ird.java", root.id(), SourceFileStatus.ANALYZED, null, null),
            new SourceFile("src/p/Fine.java", root.id(), SourceFileStatus.ANALYZED, null, null));
    final RecordingElementSink sink = new RecordingElementSink();
    final CodeElementExtractor extractor = new CodeElementExtractor(List.of(root), sink);

    extractor.begin(files);
    extractor.end(analysisOf(files));

    assertThat(ids(sink.fileElements()))
        .containsExactly("file:src/p/We\\ird.java", "file:src/p/Fine.java");
    assertThat(extractor.issues()).isEmpty();
  }

  @Test
  void umTipoSemPosicaoQueNaoEImplicitoEPerdaDoModelo() {
    final Factory factory = new Launcher().getFactory();
    final CtClass<?> lost = factory.Class().create("p.Lost");
    final CtClass<?> implicit = factory.Class().create("p.Implicit");
    implicit.setImplicit(true);
    final CtCompilationUnit unit = factory.CompilationUnit().getOrCreate("Lost.java");
    unit.addDeclaredType(lost);
    final SourceRoot root =
        new SourceRoot(SourceRootId.derive("src"), "src", SourceRootKind.MAIN, 1);
    final CodeElementExtractor extractor =
        new CodeElementExtractor(List.of(root), new RecordingElementSink());
    extractor.begin(List.of());
    final ModeledBatch batch =
        new ModeledBatch(root, List.of(), factory, temp, List.of(), SourceTrees.NEVER);

    assertThat(lost.getPosition().isValidPosition()).as("pré-condição").isFalse();
    assertThat(ElementIds.isModelLoss(lost)).isTrue();
    assertThat(ElementIds.isModelLoss(implicit)).isFalse();
    assertThatThrownBy(() -> extractor.extract(batch, "src/p/Lost.java", unit))
        .isInstanceOf(ModelLossException.class);
  }

  @Test
  void umTipoQueOSpoonTrocaPorUmVazioFazOArquivoFalharEmVezDeVirarNoVazio() throws Exception {
    final Map<String, String> files =
        Map.of(
            "src/main/java/q/Mode.java",
            """
            package q;

            enum Mode {
              NONE,
              ONE
            }
            """,
            "src/main/java/q/Printer.java",
            """
            package q;

            import static q.Mode.NONE;

            class Printer {
              void details(Item item) {
                item.each(value -> detail(NONE));
              }

              private void detail(Mode mode) {}
            }
            """);
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve("loss"), files);
    final Extraction run = extract(repository);

    assertThat(declaredTypesOf(repository, "src/main/java/q/Mode.java"))
        .containsExactly("CtClassImpl valid=false members=0");
    assertThat(run.analysis().files())
        .extracting(file -> file.relativePath() + " " + file.status() + " " + file.cause())
        .containsExactly(
            "src/main/java/q/Mode.java PARSE_FAILED EXTRACTION_CRASH",
            "src/main/java/q/Printer.java ANALYZED null");
    assertThat(run.analysis().issues())
        .containsExactly(SourceIssue.parseFailed("src/main/java/q/Mode.java"));
    assertThat(ids(run.sink().types())).containsExactly("type:q.Printer");
    assertThat(ids(run.sink().fileElements()))
        .containsExactly("file:src/main/java/q/Mode.java", "file:src/main/java/q/Printer.java");
  }

  private static final String NESTING =
      """
      package n;

      public class Outer {
        Runnable field =
            new Runnable() {
              public void run() {}
            };

        static {
          new Runnable() {
            public void run() {}
          };
        }

        void namedInAnon() {
          new Runnable() {
            class InnerOfAnon {
              void x() {}
            }

            public void run() {}
          };
        }

        void locals() {
          class LocalClass {
            LocalClass(int seed) {}

            void lm() {}
          }
          record LocalRecord(int v) {}
        }

        void anonWithCtorArgs() {
          new Thread("name") {
            public void start() {}
          };
        }

        static class Member {
          Runnable inMember =
              new Runnable() {
                public void run() {}
              };
        }

        enum Mode {
          A {
            void g() {}

            void onlyInBody() {}
          },
          B;

          void g() {}
        }
      }
      """;

  private static final String API =
      """
      package a;

      public class Api {
        public static class Inner {}
      }
      """;

  private static final String HELPER =
      """
      package b;

      public class Helper {}
      """;

  private static final String USER =
      """
      package b;

      import a.*;

      public class User {
        void plain(Api api) {}

        void array(Api[] apis) {}

        void varargs(Api... apis) {}

        void nested(Api.Inner inner) {}

        <T extends Api> void bounded(T api) {}

        void sibling(Helper helper) {}
      }
      """;

  private record Extraction(
      List<SourceRoot> roots,
      SourceAnalysis analysis,
      List<SourceIssue> issues,
      RecordingElementSink sink) {}

  private Extraction extract(Map<String, String> files) throws Exception {
    return extract(SourceTrees.repository(temp.resolve("repository"), files));
  }

  private Extraction extract(Path directory, Map<String, String> files) throws Exception {
    return extract(SourceTrees.repository(directory, files));
  }

  private static Extraction extract(ValidatedRepository repository) {
    final List<SourceRoot> roots = inVisitOrder(repository);
    final RecordingElementSink sink = new RecordingElementSink();
    final CodeElementExtractor extractor = new CodeElementExtractor(roots, sink);
    final SourceAnalysis analysis =
        new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    return new Extraction(roots, analysis, extractor.issues(), sink);
  }

  private static List<SourceRoot> inVisitOrder(ValidatedRepository repository) {
    return repository.layout().sourceRoots().stream()
        .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
        .toList();
  }

  private static List<String> userMethods(Extraction run) {
    return ids(run.sink().methods()).stream()
        .filter(id -> id.startsWith("method:b.User#"))
        .toList();
  }

  private static List<String> rawUserParameters(ValidatedRepository repository) {
    final List<String> names = new ArrayList<>();
    new SpoonModelBuilder()
        .analyze(
            repository,
            repository.layout().sourceRoots(),
            batch ->
                batch.perCompilationUnit(
                    (path, unit) -> {
                      for (final CtType<?> type : unit.getDeclaredTypes()) {
                        if (type.getSimpleName().equals("User")) {
                          type.getMethods().stream()
                              .sorted(Comparator.comparing(method -> method.getSimpleName()))
                              .forEach(method -> names.add(rawName(method.getParameters())));
                        }
                      }
                      return null;
                    }),
            SourceTrees.NEVER);
    return List.copyOf(names);
  }

  private static List<String> declaredTypesOf(ValidatedRepository repository, String path) {
    final List<String> types = new ArrayList<>();
    new SpoonModelBuilder()
        .analyze(
            repository,
            repository.layout().sourceRoots(),
            batch ->
                batch.perCompilationUnit(
                    (file, unit) -> {
                      if (file.equals(path)) {
                        for (final CtType<?> type : unit.getDeclaredTypes()) {
                          types.add(
                              type.getClass().getSimpleName()
                                  + " valid="
                                  + type.getPosition().isValidPosition()
                                  + " members="
                                  + type.getTypeMembers().size());
                        }
                      }
                      return null;
                    }),
            SourceTrees.NEVER);
    return List.copyOf(types);
  }

  private static String rawName(List<CtParameter<?>> parameters) {
    final CtTypeReference<?> type = parameters.getFirst().getType();
    if (type instanceof CtArrayTypeReference<?> array) {
      return array.getComponentType().getQualifiedName();
    }
    if (type instanceof CtTypeParameterReference variable) {
      return variable.getDeclaration().getSuperclass().getQualifiedName();
    }
    return type.getQualifiedName();
  }

  private static Map<String, String> packageOfEachFile(Extraction run) {
    final Map<String, String> packages = new LinkedHashMap<>();
    for (final FileElement file : run.sink().fileElements()) {
      packages.put(file.id().value(), String.valueOf(file.packageId()));
    }
    return packages;
  }

  private static List<Modifier> canonical(Set<Modifier> modifiers) {
    return modifiers.stream().sorted().toList();
  }

  private static SourceAnalysis analysisOf(List<SourceFile> files) {
    return new SourceAnalysis(
        files, List.of(), new SourceAnalysis.Diagnostics(0, 0, 0, 0, 0, 0, 0, "", 0));
  }
}
