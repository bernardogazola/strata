package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionHarness.Extraction;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionHarness.Layout;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToMethodGuardTest {

  @TempDir private Path temp;

  @Test
  void asFormasCertasGuardamOCalleeQueOJavacLiga() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(temp.resolve("formas"), Layout.ONE_ROOT, SHAPES, Map.of());

    assertThat(chamadas(run))
        .containsExactly(
            "p.Base L7 method:p.Base#<init>()",
            "p.Color L7 method:p.Color#mix()",
            "p.Sub L7 method:p.Base#<init>(int)",
            "p.Sub L11 method:p.Sub#<init>()",
            "p.Sub L15 method:p.Base#inherited()",
            "p.Sub L16 method:p.Base#inherited()",
            "p.Sub L17 method:p.Base#over(int)",
            "p.Sub L18 method:p.Base#over(long)",
            "p.Sub L19 method:p.Base#over(java.lang.String)",
            "p.Sub L20 method:p.Base#over(java.lang.Object)",
            "p.Sub L21 method:p.Base#over(int)",
            "p.Sub L22 method:p.Base#vararg(java.lang.String[])",
            "p.Sub L23 method:p.Base#vararg(java.lang.String[])",
            "p.Sub L24 method:p.Base#vararg(int,java.lang.String[])",
            "p.Sub L25 method:p.Base#bounded(java.lang.Comparable)",
            "p.Sub L26 method:q.Statics#helper()",
            "p.Sub L27 method:p.Base$Nested#<init>()",
            "p.Sub L27 method:p.Base$Nested#go()",
            "p.Sub L28 method:p.Sub#<init>(java.lang.String)",
            "p.Sub L29 method:p.Repo#find(int)",
            "p.Sub L30 method:p.PointRepo#save(p.Point)",
            "p.Sub L31 method:p.Repo#save(java.lang.Object)",
            "p.Sub L33 -",
            "p.Sub L37 method:p.Sub#extra()");
  }

  @Test
  void oDefaultDeUmaInterfaceDeOutraRaizNuncaViraOMetodoDeQuemEnvolve() throws Exception {
    final Map<String, String> core =
        Map.of(
            "a/Greeter.java",
            """
            package a;

            public interface Greeter {
              default void hello() {}

              void name();
            }
            """);
    final Map<String, String> app =
        Map.of(
            "b/Host.java",
            """
            package b;

            import a.Greeter;

            public class Host {
              void hello() {}

              Greeter g() {
                return new Greeter() {
                  public void name() {
                    hello();
                  }
                };
              }

              class Named implements Greeter {
                public void name() {
                  hello();
                }
              }

              Runnable lambda() {
                return () -> hello();
              }
            }
            """);

    final Extraction oneRoot =
        ResolutionHarness.extract(temp.resolve("uma"), Layout.ONE_ROOT, core, app);
    final Extraction twoRoots =
        ResolutionHarness.extract(temp.resolve("duas"), Layout.TWO_ROOTS, core, app);

    assertThat(chamadas(oneRoot))
        .as("uma raiz: o ECJ vê Greeter")
        .containsExactly(
            "b.Host L9 -",
            "b.Host L11 method:a.Greeter#hello()",
            "b.Host L23 method:b.Host#hello()",
            "b.Host$Named L18 method:a.Greeter#hello()");
    assertThat(chamadas(twoRoots))
        .as("duas raízes: o Host#hello() do recuo léxico não fica")
        .containsExactly(
            "b.Host L9 -",
            "b.Host L11 -",
            "b.Host L23 method:b.Host#hello()",
            "b.Host$Named L18 -");
  }

  @Test
  void oReceptorAnonimoOuLocalEOProprioTipoQualificador() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("local"),
            Layout.ONE_ROOT,
            Map.of(
                "p/Outer.java",
                """
                package p;

                public class Outer {
                  void m() {
                    new Object() {
                      void anon() {}
                    }.anon();
                    class Local {
                      void lm() {}
                    }
                    new Local().lm();
                  }
                }
                """),
            Map.of());

    assertThat(chamadas(run))
        .containsExactly(
            "p.Outer L5 -", "p.Outer L7 method:p.Outer#anon()", "p.Outer L11 method:p.Outer#lm()");
  }

  @Test
  void umTipoDaCadeiaQueOIndiceNaoConheceNaoDeixaOCalleeDeFora() throws Exception {
    final Map<String, String> core =
        Map.of("z/Dummy.java", "package z;\n\npublic class Dummy {}\n");
    final Map<String, String> app =
        Map.of(
            "b/Outer.java",
            """
            package b;

            import ext.Base;

            public class Outer {
              void helper() {}

              class Inner extends Base {
                void run() {
                  helper();
                }
              }

              void anon() {
                new Base() {
                  void go() {
                    helper();
                  }
                };
              }
            }
            """);

    for (final Layout layout : Layout.values()) {
      final Extraction run =
          ResolutionHarness.extract(temp.resolve(layout.name()), layout, core, app);

      assertThat(chamadas(run))
          .as(layout + ": nenhum toMethod")
          .allMatch(line -> line.endsWith(" -"));
    }
  }

  @Test
  void aSobrecargaDeUmSupertipoDeForaNaoDeixaOCalleeQueOEcjViu() throws Exception {
    final Map<String, String> core =
        Map.of(
            "a/PayloadBase.java",
            "package a;\n\npublic class PayloadBase {}\n",
            "a/Payload.java",
            "package a;\n\npublic class Payload extends PayloadBase {}\n");
    final Map<String, String> app =
        Map.of(
            "b/ExtSub.java",
            """
            package b;

            import ext.Lib;

            public class ExtSub extends Lib {
              public void log(String s) {}
            }
            """,
            "b/ExtObject.java",
            """
            package b;

            import ext.Lib;

            public class ExtObject extends Lib {
              public void log(Object o) {}
            }
            """,
            "b/ExtSuper.java",
            """
            package b;

            import a.PayloadBase;
            import ext.Lib;

            public class ExtSuper extends Lib {
              public void log(PayloadBase o) {}
            }
            """,
            "b/Api.java",
            "package b;\n\npublic interface Api {\n  default void log(String s) {}\n}\n",
            "b/Mine.java",
            "package b;\n\nimport ext.Lib;\n\npublic class Mine extends Lib implements Api {}\n",
            "b/Caller.java",
            """
            package b;

            import a.Payload;

            public class Caller {
              void m(ExtSub e, ExtObject o, ExtSuper s, Mine mine, Payload p) {
                e.log(p);
                o.log(p);
                s.log(p);
                mine.log(p);
              }
            }
            """);

    for (final Layout layout : Layout.values()) {
      final Extraction run =
          ResolutionHarness.extract(temp.resolve(layout.name()), layout, core, app);

      assertThat(chamadas(run))
          .as(layout.toString())
          .containsExactly("b.Caller L7 -", "b.Caller L8 -", "b.Caller L9 -", "b.Caller L10 -");
    }
  }

  @Test
  void asSobrecargasErradasComUmTipoDeOutroLoteDaoNulo() throws Exception {
    final Map<String, String> core =
        Map.of(
            "a/FailableSupplier.java",
            "package a;\n\npublic interface FailableSupplier {\n  Object get();\n}\n",
            "a/TimeZones.java",
            """
            package a;

            import java.util.TimeZone;

            public final class TimeZones {
              public static final TimeZone GMT = TimeZone.getTimeZone("GMT");
            }
            """);
    final Map<String, String> app =
        Map.of(
            "b/Checks.java",
            """
            package b;

            import a.FailableSupplier;
            import java.util.Locale;
            import java.util.TimeZone;

            public final class Checks {
              public static Object requireNonNull(Object o, String message) {
                return o;
              }

              public static Object requireNonNull(Object o, FailableSupplier supplier) {
                return o;
              }

              public static Checks getInstance(String pattern, TimeZone zone) {
                return null;
              }

              public static Checks getInstance(String pattern, Locale locale) {
                return null;
              }
            }
            """,
            "b/User.java",
            """
            package b;

            import a.TimeZones;

            public class User {
              void m() {
                Checks.requireNonNull("foo", "bar");
                Checks.getInstance("x", TimeZones.GMT);
              }
            }
            """);

    final Extraction oneRoot =
        ResolutionHarness.extract(temp.resolve("uma"), Layout.ONE_ROOT, core, app);
    final Extraction twoRoots =
        ResolutionHarness.extract(temp.resolve("duas"), Layout.TWO_ROOTS, core, app);

    assertThat(chamadas(oneRoot))
        .containsExactly(
            "a.TimeZones L6 -",
            "b.User L7 method:b.Checks#requireNonNull(java.lang.Object,java.lang.String)",
            "b.User L8 method:b.Checks#getInstance(java.lang.String,java.util.TimeZone)");
    assertThat(chamadas(twoRoots))
        .containsExactly("a.TimeZones L6 -", "b.User L7 -", "b.User L8 -");
  }

  @Test
  void semAProvaDaIgualdadeAteOCalleeCertoENulo() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("preco"),
            Layout.ONE_ROOT,
            Map.of(
                "b/Plain.java",
                """
                package b;

                import ext.Quiet;

                public class Plain extends Quiet {
                  public void log(Object o) {}
                }
                """),
            Map.of(
                "b/PlainUser.java",
                """
                package b;

                public class PlainUser {
                  void m(Plain plain, Object any) {
                    plain.log("s");
                    plain.log(any);
                  }
                }
                """));

    assertThat(chamadas(run))
        .containsExactly("b.PlainUser L5 -", "b.PlainUser L6 method:b.Plain#log(java.lang.Object)");
  }

  private static final Map<String, String> SHAPES =
      Map.of(
          "p/Base.java",
          """
          package p;

          public class Base<T> {
            public Base() {}

            public Base(int x) {
              this();
            }

            public void inherited() {}

            public void over(int x) {}

            public void over(long x) {}

            public void over(Object o) {}

            public void over(String s) {}

            public void vararg(String... xs) {}

            public void vararg(int a, String... xs) {}

            public <U extends Comparable<U>> U bounded(U u) {
              return u;
            }

            public static class Nested {
              public void go() {}
            }
          }
          """,
          "p/Repo.java",
          """
          package p;

          public class Repo<E> {
            public E find(int id) {
              return null;
            }

            public void save(E entity) {}
          }
          """,
          "p/PointRepo.java",
          """
          package p;

          public class PointRepo extends Repo<Point> {
            @Override
            public void save(Point entity) {}
          }
          """,
          "p/Point.java",
          "package p;\n\npublic record Point(int x, int y) {}\n",
          "p/Color.java",
          """
          package p;

          public enum Color {
            RED {
              @Override
              void paint() {
                mix();
              }
            };

            void paint() {}

            void mix() {}
          }
          """,
          "q/Statics.java",
          """
          package q;

          public final class Statics {
            private Statics() {}

            public static void helper() {}
          }
          """,
          "p/Sub.java",
          """
          package p;

          import static q.Statics.helper;

          public class Sub extends Base<String> {
            public Sub() {
              super(1);
            }

            public Sub(String s) {
              this();
            }

            void calls(Sub sub, PointRepo points, Repo<Point> repo, Point point) {
              sub.inherited();
              inherited();
              over(1);
              over(1L);
              over("s");
              over((Object) "s");
              over('c');
              vararg();
              vararg("a", "b");
              vararg(1, "a");
              bounded("x");
              helper();
              new Base.Nested().go();
              new Sub("x");
              points.find(1);
              points.save(point);
              repo.save(point);
              Runnable r =
                  new Runnable() {
                    void extra() {}

                    public void run() {
                      extra();
                    }
                  };
            }
          }
          """);

  private static List<String> chamadas(Extraction run) {
    final List<Chamada> found = new ArrayList<>();
    for (final ExtractedDependency row : run.sink().rows()) {
      if (row.dependency().type() != DependencyType.CALLS) {
        continue;
      }
      for (final DependencyEvidence evidence : row.evidence()) {
        found.add(
            new Chamada(
                row.dependency().source().sortKey(),
                evidence.line(),
                evidence.toMethod() == null ? "-" : evidence.toMethod().value()));
      }
    }
    return found.stream()
        .sorted(
            Comparator.comparing(Chamada::source)
                .thenComparingInt(Chamada::line)
                .thenComparing(Chamada::toMethod))
        .map(call -> call.source() + " L" + call.line() + " " + call.toMethod())
        .toList();
  }

  private record Chamada(String source, int line, String toMethod) {}
}
