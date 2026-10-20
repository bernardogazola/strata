package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CallTargetsTest {

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String CLIENT_IMPORT = "import com.thirdparty.Client;\n";

  private static final String ORDER =
      """
      package p;

      public class Order {

        public Order() {}

        public int total() {
          return 0;
        }
      }
      """;

  private static final String LATE =
      """
      package q;

      public class Late {

        public static final Runnable CONST = () -> {};

        public Late() {}

        public static class Inner {

          public Inner() {}

          public static void go() {}
        }
      }
      """;

  @TempDir private Path temp;

  @Test
  void oThisSemTipoDeUmLambdaRecuperaPeloImportEstaticoOuPelaOrigem() throws Exception {
    final Run run =
        extract(
            Map.of(
                "src/main/java/p/Hub.java",
                """
                package p;

                import static com.thirdparty.Util.go;

                import com.thirdparty.Client;

                public class Hub {

                  private Client client;

                  public Hub() {}

                  public void wire() {
                    client.onEvent(e -> go(e));
                    client.onEvent(e -> helper(e));
                    client.onEvent(e -> this.helper(e));
                  }

                  private void helper(Object event) {}
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Hub -> com.thirdparty.Client IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "p.Hub -> com.thirdparty.Client CALLS x3 UNRESOLVED_EXTERNAL external",
            "p.Hub -> com.thirdparty.Client FIELD_TYPE x1 UNRESOLVED_EXTERNAL external",
            "p.Hub -> com.thirdparty.Util IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "p.Hub -> com.thirdparty.Util CALLS x1 UNRESOLVED_EXTERNAL external",
            "p.Hub -> java.lang.Object PARAMETER_TYPE x1 RESOLVED external",
            "p.Hub -> p.Hub CALLS x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Hub", "com.thirdparty.Util", "CALLS")))
        .containsExactly("L14 method:p.Hub#wire()");
    assertThat(evidence(run.sink().row("p.Hub", "p.Hub", "CALLS")))
        .containsExactly("L15 method:p.Hub#wire()");
    assertThat(run.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 1L);
  }

  @Test
  void oReceptorCuringaOuVariavelDeTipoTemPorQualificadorOLimite() throws Exception {
    final Run run =
        extract(
            Map.of(
                "src/main/java/p/Shelf.java",
                """
                package p;

                import java.util.List;

                public class Shelf<T extends Base> {

                  private T item;
                  private List<Order> orders;
                  private List<? extends Order> upper;

                  public Shelf() {}

                  public void read() {
                    item.hook();
                    orders.get(0).total();
                    upper.get(0).total();
                    upper.get(1).total();
                  }
                }
                """,
                "src/main/java/p/Base.java",
                "package p;\n\npublic class Base {\n\n  public Base() {}\n\n  public void hook() {}\n}\n",
                "src/main/java/p/Order.java",
                "package p;\n\npublic class Order {\n\n  public Order() {}\n\n"
                    + "  public int total() {\n    return 0;\n  }\n}\n"));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Shelf -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Shelf -> java.util.List CALLS x3 RESOLVED external",
            "p.Shelf -> java.util.List FIELD_TYPE x2 RESOLVED external",
            "p.Shelf -> p.Base CALLS x1 RESOLVED",
            "p.Shelf -> p.Order CALLS x3 RESOLVED",
            "p.Shelf -> p.Order FIELD_TYPE x2 RESOLVED");
  }

  @Test
  void cadaDescarteEContadoPeloMotivo() throws Exception {
    final String longName = "L" + "o".repeat(4_100) + "ng";
    final Run run =
        extract(
            Map.of(
                "src/main/java/Bootstrap.java",
                "public class Bootstrap {\n\n  public Bootstrap() {}\n}\n",
                "src/main/java/p/Wide.java",
                "package p;\n\npublic class Wide {\n\n  public Wide() {}\n}\n\nclass "
                    + longName
                    + " {}\n",
                "src/main/java/p/Probe.java",
                """
                package p;

                %s
                public class Probe {

                  private Bootstrap boot;
                  private %s wide;
                  private Client client;

                  public Probe() {}

                  public void run(int[] counts) {
                    class Local {}
                    Object made = new Local();
                    counts.clone();
                    client.send().ack();
                  }
                }
                """
                    .formatted(CLIENT_IMPORT, longName)));

    assertThat(run.drops())
        .containsExactly(
            Map.entry(DropReason.LOCAL_TARGET, 1L),
            Map.entry(DropReason.INVALID_NAME, 1L),
            Map.entry(DropReason.ID_TOO_LONG, 1L),
            Map.entry(DropReason.DEFAULT_PACKAGE_COLLISION, 1L),
            Map.entry(DropReason.CALL_WITHOUT_TARGET, 1L));
    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Probe -> com.thirdparty.Client IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "p.Probe -> com.thirdparty.Client CALLS x1 UNRESOLVED_EXTERNAL external",
            "p.Probe -> com.thirdparty.Client FIELD_TYPE x1 UNRESOLVED_EXTERNAL external");
    assertThatThrownBy(() -> run.extractor().droppedOccurrences().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void oAcessorTemOsCincoMotivosDesdeOComeco() {
    assertThat(new StructureExtractor(List.of(), new RecordingStructureSink()).droppedOccurrences())
        .containsOnlyKeys(DropReason.values())
        .allSatisfy((reason, count) -> assertThat(count).isZero());
  }

  @Test
  void oLocalVarQueOSpoonApagaParaObjectSeQualificaPeloInicializador() throws Exception {
    final String vars =
        """
        package p;

        public class Vars {

          public Vars() {}

          public void m() {
            var local = new Order();
            local.total();
            var plain = new Object();
            plain.hashCode();
          }
        }
        """;

    final Run oneRoot =
        extract(
            "one-root",
            Map.of("src/main/java/p/Vars.java", vars, "src/main/java/p/Order.java", ORDER));
    final Run twoRoots =
        extract(
            "two-roots",
            Map.of("src/main/java/p/Vars.java", vars, "src/test/java/p/Order.java", ORDER));

    assertThat(oneRoot.sink().tuples())
        .containsExactly(
            "p.Vars -> java.lang.Object CALLS x2 RESOLVED external",
            "p.Vars -> p.Order CALLS x2 RESOLVED");
    assertThat(twoRoots.sink().tuples())
        .containsExactly(
            "p.Vars -> java.lang.Object CALLS x2 RESOLVED external",
            "p.Vars -> p.Order CALLS x2 INFERRED");
    assertThat(twoRoots.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
  }

  @Test
  void oLocalVarSemInicializadorQueSirvaEDescartadoENaoViraObject() throws Exception {
    final Run run =
        extract(
            Map.of(
                "src/main/java/p/Each.java",
                """
                package p;

                import java.util.List;

                public class Each {

                  public Each() {}

                  public void m() {
                    for (var each : List.of(new Order())) {
                      each.total();
                    }
                  }
                }
                """,
                "src/test/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Each -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Each -> java.util.List CALLS x1 RESOLVED external",
            "p.Each -> p.Order CALLS x1 INFERRED");
    assertThat(run.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 1L);
  }

  @Test
  void oCampoEstaticoLidoPorUmTipoDeOutroLoteNaoInventaLinhaParaQuemChama() throws Exception {
    final String user =
        """
        package p;

        import q.Late;

        public class User {

          private final Runnable task =
              new Runnable() {
                @Override
                public void run() {
                  Late.CONST.run();
                }
              };

          public User() {}

          public void m() {
            Late.CONST.run();
            Late.Inner.go();
          }

          class In {

            void n() {
              Late.CONST.run();
            }
          }
        }
        """;

    final Run oneRoot =
        extract(
            "one-root",
            Map.of("src/main/java/p/User.java", user, "src/main/java/q/Late.java", LATE));
    final Run twoRoots =
        extract(
            "two-roots",
            Map.of("src/main/java/p/User.java", user, "src/test/java/q/Late.java", LATE));

    assertThat(oneRoot.sink().tuples())
        .containsExactly(
            "p.User -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.User -> java.lang.Runnable CALLS x3 RESOLVED external",
            "p.User -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external",
            "p.User -> q.Late IMPORTS x1 RESOLVED",
            "p.User -> q.Late$Inner CALLS x1 RESOLVED",
            "p.User$In -> java.lang.Runnable CALLS x1 RESOLVED external",
            "q.Late -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external");
    assertThat(oneRoot.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
    assertThat(twoRoots.sink().tuples())
        .containsExactly(
            "p.User -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.User -> java.lang.Runnable CALLS x3 RESOLVED external",
            "p.User -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external",
            "p.User -> q.Late IMPORTS x1 INFERRED",
            "p.User -> q.Late$Inner CALLS x1 INFERRED",
            "p.User$In -> java.lang.Runnable CALLS x1 RESOLVED external",
            "q.Late -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external");
    assertThat(twoRoots.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
  }

  @Test
  void umCampoEstaticoDoTipoDeQuemChamaDeclaradoNoutroTipoDaALinhaNasDuasRaizes() throws Exception {
    final String account =
        """
        package p;

        import q.Registry;

        public class Account {

          public Account() {}

          public void touch() {
            Registry.current.touch();
          }
        }
        """;
    final String registry =
        """
        package q;

        import p.Account;

        public final class Registry {

          public static Account current;

          private Registry() {}
        }
        """;

    final Run oneRoot =
        extract(
            "one-root",
            Map.of(
                "src/main/java/p/Account.java",
                account,
                "src/main/java/q/Registry.java",
                registry));
    final Run twoRoots =
        extract(
            "two-roots",
            Map.of(
                "src/main/java/p/Account.java",
                account,
                "src/test/java/q/Registry.java",
                registry));

    assertThat(oneRoot.sink().tuples())
        .containsExactly(
            "p.Account -> p.Account CALLS x1 RESOLVED",
            "p.Account -> q.Registry IMPORTS x1 RESOLVED",
            "q.Registry -> p.Account IMPORTS x1 RESOLVED",
            "q.Registry -> p.Account FIELD_TYPE x1 RESOLVED");
    assertThat(twoRoots.sink().tuples())
        .containsExactly(
            "p.Account -> p.Account CALLS x1 INFERRED",
            "p.Account -> q.Registry IMPORTS x1 INFERRED",
            "q.Registry -> p.Account IMPORTS x1 INFERRED",
            "q.Registry -> p.Account FIELD_TYPE x1 INFERRED");
    assertThat(oneRoot.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
    assertThat(twoRoots.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
  }

  @Test
  void oReceptorDoTipoNuloDoSpoonEDesconhecidoENaoUmNomeInvalido() throws Exception {
    final Run run =
        extract(
            Map.of(
                "src/main/java/p/Nulls.java",
                """
                package p;

                public class Nulls {

                  public Nulls() {}

                  public void m() {
                    null.toString();
                    (true ? null : null).hashCode();
                  }
                }
                """));

    assertThat(run.sink().tuples()).isEmpty();
    assertThat(run.drops())
        .containsEntry(DropReason.INVALID_NAME, 0L)
        .containsEntry(DropReason.CALL_WITHOUT_TARGET, 2L);
  }

  @Test
  void aEvidenciaDeUmaCadeiaEstaNaLinhaDoNomeDeCadaMetodo() throws Exception {
    final Run run =
        extract(
            Map.of(
                "src/main/java/p/Chain.java",
                """
                package p;

                public class Chain {

                  public Chain() {}

                  public String m(StringBuilder sb) {
                    return sb
                        .append("a")
                        .append("b")
                        .toString();
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Chain -> java.lang.String RETURN_TYPE x1 RESOLVED external",
            "p.Chain -> java.lang.StringBuilder CALLS x3 RESOLVED external",
            "p.Chain -> java.lang.StringBuilder PARAMETER_TYPE x1 RESOLVED external");
    assertThat(evidence(run.sink().row("p.Chain", "java.lang.StringBuilder", "CALLS")))
        .containsExactly(
            "L9 method:p.Chain#m(java.lang.StringBuilder)",
            "L10 method:p.Chain#m(java.lang.StringBuilder)",
            "L11 method:p.Chain#m(java.lang.StringBuilder)");
  }

  @Test
  void oDescarteDoPerdedorDeUmaRepeticaoNaoEContado() throws Exception {
    final String dup =
        """
        package p;

        public class Dup {

          public Dup() {}

          public void m() {
            class Local {}
            Object made = new Local();
          }
        }
        """;

    final Run run =
        extract(Map.of("src/main/java/p/Dup.java", dup, "src/test/java/p/Dup.java", dup));

    assertThat(run.sink().tuples()).isEmpty();
    assertThat(run.drops()).containsEntry(DropReason.LOCAL_TARGET, 1L);
  }

  @Test
  void osDescartesVaoAoLogUmaVezPorAnaliseSoComAsContagens() throws Exception {
    final String local =
        """
        package p;

        public class %s {

          public %s() {}

          public void m() {
            class Local {}
            Object made = new Local();
          }
        }
        """;

    final List<String> dropped =
        droppedLogLines(
            "dropping",
            Map.of(
                "src/main/java/p/First.java", local.formatted("First", "First"),
                "src/main/java/p/Second.java", local.formatted("Second", "Second")));
    final List<String> clean =
        droppedLogLines("clean", Map.of("src/main/java/p/Order.java", ORDER));

    assertThat(dropped).hasSize(1);
    assertThat(dropped.getFirst()).contains("LOCAL_TARGET=2").doesNotContain(".java");
    assertThat(clean).isEmpty();
  }

  @Test
  void oReceptorQueOSpoonFazDeTipoEAVariavelAVistaPeloEscopoDaJls() throws Exception {
    final String scopes =
        """
        package p;

        public class Scopes {
          void m(org.ext.Missing missing) {
            missing.run(
                () -> {
                  Object o = missing.get();
                  if (o instanceof Integer i && i.intValue() > 0) {}
                  if (!(o instanceof Long l)) {
                  } else {
                    l.longValue();
                  }
                  if (!(o instanceof Short sh)) {
                    return;
                  }
                  sh.shortValue();
                  double value = o instanceof Double d ? d.doubleValue() : 0;
                  if (!(o instanceof Float f) || f.isNaN()) {}
                  switch (o) {
                    case Byte b -> b.byteValue();
                    default -> {}
                  }
                  try {
                    missing.call();
                  } catch (IllegalStateException e) {
                    new Object() {
                      Runnable e = null;

                      void g() {
                        e.run();
                      }
                    };
                  }
                  if (o instanceof Character c) {}
                  c.charValue();
                });
          }
        }
        """;

    final Run run = extract(Map.of("src/main/java/p/Scopes.java", scopes));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Scopes -> c CALLS x1 UNRESOLVED_EXTERNAL external",
            "p.Scopes -> java.lang.Byte CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Double CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Float CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Integer CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Long CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Object CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Runnable CALLS x1 RESOLVED external",
            "p.Scopes -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external",
            "p.Scopes -> java.lang.Short CALLS x1 RESOLVED external",
            "p.Scopes -> org.ext.Missing CALLS x3 UNRESOLVED_EXTERNAL external",
            "p.Scopes -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external");
    assertThat(run.drops()).containsEntry(DropReason.CALL_WITHOUT_TARGET, 0L);
  }

  private record Run(StructureExtractor extractor, RecordingStructureSink sink) {

    Map<DropReason, Long> drops() {
      return extractor.droppedOccurrences();
    }
  }

  private Run extract(Map<String, String> files) throws Exception {
    return extract("repository", files);
  }

  private Run extract(String directory, Map<String, String> files) throws Exception {
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve(directory), files);
    final List<SourceRoot> roots =
        repository.layout().sourceRoots().stream()
            .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
            .toList();
    final RecordingStructureSink sink = new RecordingStructureSink();
    final StructureExtractor extractor = new StructureExtractor(roots, sink);
    new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    return new Run(extractor, sink);
  }

  private List<String> droppedLogLines(String directory, Map<String, String> files)
      throws Exception {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      extract(directory, files);
    } finally {
      System.setErr(original);
    }
    return captured
        .toString(StandardCharsets.UTF_8)
        .lines()
        .filter(line -> line.contains("descart"))
        .toList();
  }
}
