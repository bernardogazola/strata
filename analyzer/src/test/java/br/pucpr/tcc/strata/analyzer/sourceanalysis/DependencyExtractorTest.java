package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.evidence;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.tuple;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DependencyExtractorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String ORDER =
      """
      package p;

      public class Order {

        public Order() {}
      }
      """;

  private static final String ORDER_WITH_TOTAL =
      """
      package p;

      public class Order {

        public Order() {}

        public int total() {
          return 0;
        }
      }
      """;

  private static final String ENGINE =
      """
      package p;

      public class Engine {

        public Engine() {}

        public void start() {}

        public void stop() {}
      }
      """;

  private static final String THING =
      """
      package q;

      public class Thing {

        public Thing() {}
      }
      """;

  private static final String UTIL =
      """
      package q;

      public final class Util {

        private Util() {}

        public static void helper() {}
      }
      """;

  @TempDir private Path temp;

  @Test
  void umLoteChegaNumaChamadaSoComElementosEDependencias() throws Exception {
    final Extraction run =
        extract(SourceTrees.fixture("method-calls", temp.toRealPath().resolve("fixture")));

    assertThat(run.sink().begun()).hasSize(4);
    assertThat(run.sink().ends()).isEqualTo(1);
    assertThat(run.sink().accepted()).hasSize(1);
    final BatchStructure batch = run.sink().accepted().getFirst();
    assertThat(batch.elements().types())
        .extracting(type -> type.id().value())
        .containsExactlyInAnyOrder(
            "type:a.b.Order", "type:a.c.OrderRepository", "type:a.c.OrderService", "type:a.c.Task");
    assertThat(batch.dependencies().dependencies()).hasSize(9);
    assertThat(batch.dependencies().modules()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"broken-source", "cyclic", "inheritance", "method-calls"})
  void osQuatroGoldensSaoReproduzidosLinhaALinha(String fixture) throws Exception {
    final Extraction run =
        extract(SourceTrees.fixture(fixture, temp.toRealPath().resolve("fixture")));

    assertThat(run.sink().tuples())
        .as(fixture + ": (from, to, type, occurrences, resolution, external), exaustivo")
        .containsExactlyElementsOf(goldenTuples(golden(fixture)));
  }

  @Test
  void aEvidenciaDoMethodCallsSaoAsQuinzeLinhasDoGolden() throws Exception {
    final Extraction run =
        extract(SourceTrees.fixture("method-calls", temp.toRealPath().resolve("fixture")));

    final List<String> gravadas = new ArrayList<>();
    for (final ExtractedDependency row : run.sink().rows()) {
      for (final String entry : evidence(row)) {
        gravadas.add(head(row) + " " + row.filePath() + " " + entry);
      }
    }
    final List<String> esperadas = new ArrayList<>();
    for (final JsonNode row : golden("method-calls").get("evidence")) {
      esperadas.add(
          bare(row.get("from").asText())
              + " -> "
              + bare(row.get("to").asText())
              + " "
              + row.get("type").asText()
              + " "
              + row.get("file").asText()
              + " L"
              + row.get("line").asInt()
              + " "
              + (row.has("fromMethod") ? row.get("fromMethod").asText() : "-"));
    }

    assertThat(esperadas).as("o golden mesmo tem as 15 linhas").hasSize(15);
    assertThat(gravadas).containsExactlyElementsOf(esperadas);
  }

  @Test
  void importSobDemandaDePacoteNaoProduzLinha() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Client.java",
                """
                package p;

                import q.*;

                public class Client {

                  private Thing thing;

                  public Client() {}
                }
                """,
                "src/main/java/q/Thing.java",
                THING));

    assertThat(run.sink().tuples()).containsExactly("p.Client -> q.Thing FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void importSobDemandaDePacoteForaDoModeloNaoProduzLinha() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Client.java",
                """
                package p;

                import com.thirdparty.*;

                public class Client {

                  private java.util.List<String> names;

                  public Client() {}
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "p.Client -> java.util.List FIELD_TYPE x1 RESOLVED external");
  }

  @Test
  void importEstaticoSimplesNomeiaOTipo() throws Exception {
    final Extraction run = extract(staticImportCase("import static q.Util.helper;"));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> q.Util IMPORTS x1 RESOLVED", "p.Client -> q.Util CALLS x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Client", "q.Util", "IMPORTS"))).containsExactly("L3 -");
    assertThat(evidence(run.sink().row("p.Client", "q.Util", "CALLS")))
        .containsExactly("L10 method:p.Client#run()");
  }

  @Test
  void importEstaticoSobDemandaTambemNomeiaOTipo() throws Exception {
    final Extraction run = extract(staticImportCase("import static q.Util.*;"));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> q.Util IMPORTS x1 RESOLVED", "p.Client -> q.Util CALLS x1 RESOLVED");
  }

  @Test
  void importRepetidoContaUmaVez() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Client.java",
                """
                package p;

                import q.Thing;
                import q.Thing;

                public class Client {

                  private Thing thing;

                  public Client() {}
                }
                """,
                "src/main/java/q/Thing.java",
                THING));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> q.Thing IMPORTS x1 RESOLVED",
            "p.Client -> q.Thing FIELD_TYPE x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Client", "q.Thing", "IMPORTS"))).containsExactly("L3 -");
  }

  @Test
  void importDeTipoAninhadoUsaDolar() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Client.java",
                """
                package p;

                import q.Outer.Inner;

                public class Client {

                  private Inner inner;

                  public Client() {}
                }
                """,
                "src/main/java/q/Outer.java",
                """
                package q;

                public class Outer {

                  public Outer() {}

                  public static class Inner {

                    public Inner() {}
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> q.Outer$Inner IMPORTS x1 RESOLVED",
            "p.Client -> q.Outer$Inner FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void importSobDemandaDeTipoNomeiaOTipo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Client.java",
                """
                package p;

                import q.Api.*;

                public class Client {

                  public Client() {}
                }
                """,
                "src/main/java/q/Api.java",
                """
                package q;

                public class Api {

                  public Api() {}

                  public static class Inner {

                    public Inner() {}
                  }
                }
                """));

    assertThat(run.sink().tuples()).containsExactly("p.Client -> q.Api IMPORTS x1 RESOLVED");
  }

  @Test
  void importsDeUmArquivoComDoisTiposVaoParaODoNomeDoArquivo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Primary.java",
                """
                package p;

                import java.util.List;
                import q.Thing;

                public class Primary {

                  private List<String> names;

                  public Primary() {}
                }

                class Helper {

                  private Thing thing;

                  Helper() {}
                }
                """,
                "src/main/java/q/Thing.java",
                THING));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Helper -> q.Thing FIELD_TYPE x1 RESOLVED",
            "p.Primary -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "p.Primary -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Primary -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.Primary -> q.Thing IMPORTS x1 RESOLVED");
  }

  @Test
  void oDonoDosImportsEODoNomeDoArquivoMesmoDepoisDeOutroTipo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Multi.java",
                """
                package p;

                import java.util.List;

                class Helper {

                  private List<Order> orders;

                  Helper() {}
                }

                public class Multi {

                  private Helper helper;

                  public Multi() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Helper -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.Helper -> p.Order FIELD_TYPE x1 RESOLVED",
            "p.Multi -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Multi -> p.Helper FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void argumentosDeTipoDesembrulhamRecursivamente() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Ledger.java",
                """
                package p;

                import java.util.List;
                import java.util.Map;

                public class Ledger {

                  private Map<String, List<Order>> byCustomer;

                  public Ledger() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Ledger -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "p.Ledger -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Ledger -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.Ledger -> java.util.Map IMPORTS x1 RESOLVED external",
            "p.Ledger -> java.util.Map FIELD_TYPE x1 RESOLVED external",
            "p.Ledger -> p.Order FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void vetorDesembrulhaNoComponenteEPrimitivoNaoConta() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Grid.java",
                """
                package p;

                import java.util.List;

                public class Grid {

                  private Order[][] cells;
                  private int[] counts;
                  private List<int[]> rows;

                  public Grid() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Grid -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Grid -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.Grid -> p.Order FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void curingaComLimiteContaOLimiteECuringaNuNao() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Bins.java",
                """
                package p;

                import java.util.List;

                public class Bins {

                  private List<? extends Order> upper;
                  private List<? super Order> lower;
                  private List<?> any;

                  public Bins() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Bins -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Bins -> java.util.List FIELD_TYPE x3 RESOLVED external",
            "p.Bins -> p.Order FIELD_TYPE x2 RESOLVED");
  }

  @Test
  void limiteDeParametroDeTipoEVariavelDeTipoNaoContam() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Box.java",
                """
                package p;

                import java.util.List;

                public class Box<T extends Base> {

                  private T value;

                  public Box() {}

                  public <U extends Order> U pick(U chosen, List<T> all) {
                    return chosen;
                  }
                }
                """,
                "src/main/java/p/Base.java",
                """
                package p;

                public class Base {

                  public Base() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Box -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Box -> java.util.List PARAMETER_TYPE x1 RESOLVED external");
  }

  @Test
  void posicoesExcluidasNaoProduzemLinha() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Audit.java",
                """
                package p;

                public class Audit {

                  public Audit() {}

                  public void check(Object candidate) throws AuditException {
                    Order local = null;
                    Object cast = (Order) candidate;
                    if (candidate instanceof Order typed) {
                      local = typed;
                    }
                    boolean plain = candidate instanceof Order;
                    try {
                      cast = local;
                    } catch (IllegalStateException failure) {
                      cast = null;
                    }
                    Object literal = Order.class;
                    int max = Limits.MAX;
                  }
                }
                """,
                "src/main/java/p/AuditException.java",
                """
                package p;

                public class AuditException extends Exception {

                  public AuditException() {}
                }
                """,
                "src/main/java/p/Limits.java",
                """
                package p;

                public final class Limits {

                  public static final int MAX = 1;

                  private Limits() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Audit -> java.lang.Object PARAMETER_TYPE x1 RESOLVED external",
            "p.AuditException -> java.lang.Exception EXTENDS x1 RESOLVED external");
  }

  @Test
  void declaracaoComDoisNomesEAlvoRepetidoContamUmaVez() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Pairs.java",
                """
                package p;

                import java.util.Map;

                public class Pairs {

                  private Order first, second;

                  public Pairs() {}

                  public Map<Order, Order> index(Order left, Order right) {
                    return null;
                  }
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Pairs -> java.util.Map IMPORTS x1 RESOLVED external",
            "p.Pairs -> java.util.Map RETURN_TYPE x1 RESOLVED external",
            "p.Pairs -> p.Order FIELD_TYPE x1 RESOLVED",
            "p.Pairs -> p.Order PARAMETER_TYPE x2 RESOLVED",
            "p.Pairs -> p.Order RETURN_TYPE x1 RESOLVED");
  }

  @Test
  void varargsEMetodoDeInterfaceContam() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Port.java",
                """
                package p;

                public interface Port {

                  Order fetch(Order... keys);

                  default int size(Order[] all) {
                    return all.length;
                  }
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Port -> p.Order PARAMETER_TYPE x2 RESOLVED",
            "p.Port -> p.Order RETURN_TYPE x1 RESOLVED");
  }

  @Test
  void qualificadorDeAninhadoNaoEDesembrulhado() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Cache.java",
                """
                package p;

                import java.util.Map;

                public class Cache {

                  private Map.Entry<String, Order> last;

                  public Cache() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Cache -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "p.Cache -> java.util.Map IMPORTS x1 RESOLVED external",
            "p.Cache -> java.util.Map$Entry FIELD_TYPE x1 RESOLVED external",
            "p.Cache -> p.Order FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void componenteDeRecordContaUmaVezComoCampo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Invoice.java",
                """
                package p;

                import java.util.List;

                public record Invoice(Order order, List<Line> lines, int total) implements Billable {

                  public Invoice {}

                  public Order order() {
                    return order;
                  }
                }
                """,
                "src/main/java/p/Billable.java",
                "package p;\n\npublic interface Billable {}\n",
                "src/main/java/p/Line.java",
                "package p;\n\npublic class Line {\n\n  public Line() {}\n}\n",
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Invoice -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Invoice -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.Invoice -> p.Billable IMPLEMENTS x1 RESOLVED",
            "p.Invoice -> p.Line FIELD_TYPE x1 RESOLVED",
            "p.Invoice -> p.Order FIELD_TYPE x1 RESOLVED",
            "p.Invoice -> p.Order RETURN_TYPE x1 RESOLVED");
  }

  @Test
  void anotacaoDeComponenteDeRecordContaUmaVez() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Tagged.java",
                "package p;\n\npublic record Tagged(@Deprecated Order order) {}\n",
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Tagged -> java.lang.Deprecated ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Tagged -> p.Order FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void constanteDeEnumNaoEChamadaMasOCorpoEOsArgumentosContam() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Color.java",
                """
                package p;

                public enum Color implements Shade {
                  RED(Palette.code()),
                  GREEN(2) {
                    @Override
                    public int tone() {
                      return Palette.code();
                    }
                  };

                  private final int code;

                  Color(int code) {
                    this.code = code;
                  }

                  @Override
                  public int tone() {
                    return code;
                  }
                }
                """,
                "src/main/java/p/Shade.java",
                "package p;\n\npublic interface Shade {\n\n  int tone();\n}\n",
                "src/main/java/p/Palette.java",
                """
                package p;

                public final class Palette {

                  private Palette() {}

                  public static int code() {
                    return 1;
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Color -> java.lang.Override ANNOTATION_REFERENCE x2 RESOLVED external",
            "p.Color -> p.Palette CALLS x2 RESOLVED",
            "p.Color -> p.Shade IMPLEMENTS x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Color", "p.Palette", "CALLS")))
        .containsExactly("L4 -", "L8 method:p.Color#tone()");
  }

  @Test
  void anotacaoContaNasQuatroPosicoesEDeclararUmaAnotacaoNao() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Marker.java",
                """
                package p;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public @interface Marker {

                  String value() default "";
                }
                """,
                "src/main/java/p/Service.java",
                """
                package p;

                @Marker("type")
                public class Service {

                  @Marker private int count;

                  @Deprecated
                  public Service() {}

                  @Marker
                  public void run(@Marker int times) {
                    @Marker int local = times;
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Marker -> java.lang.String RETURN_TYPE x1 RESOLVED external",
            "p.Marker -> java.lang.annotation.Retention IMPORTS x1 RESOLVED external",
            "p.Marker -> java.lang.annotation.Retention ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Marker -> java.lang.annotation.RetentionPolicy IMPORTS x1 RESOLVED external",
            "p.Service -> java.lang.Deprecated ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Service -> p.Marker ANNOTATION_REFERENCE x4 RESOLVED");
    assertThat(evidence(run.sink().row("p.Service", "p.Marker", "ANNOTATION_REFERENCE")))
        .containsExactly(
            "L3 -", "L6 -", "L11 method:p.Service#run(int)", "L12 method:p.Service#run(int)");
  }

  @Test
  void anotacaoDeParametroDeLambdaNaoConta() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Listener.java",
                """
                package p;

                import java.util.function.Consumer;

                public class Listener {

                  public Listener() {}

                  public void listen(@Deprecated Order first) {
                    Consumer<Order> sink = (@Deprecated Order order) -> {};
                  }
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Listener -> java.lang.Deprecated ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Listener -> java.util.function.Consumer IMPORTS x1 RESOLVED external",
            "p.Listener -> p.Order PARAMETER_TYPE x1 RESOLVED");
  }

  @Test
  void argumentoDeTipoDoSupertipoNaoConta() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Orders.java",
                """
                package p;

                import java.util.ArrayList;

                public abstract class Orders extends ArrayList<Order> implements Comparable<Orders> {

                  public Orders() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Orders -> java.lang.Comparable IMPLEMENTS x1 RESOLVED external",
            "p.Orders -> java.util.ArrayList IMPORTS x1 RESOLVED external",
            "p.Orders -> java.util.ArrayList EXTENDS x1 RESOLVED external");
  }

  @Test
  void supertiposDeUmaClasseLocalNaoDaoLinhaAoDono() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Host.java",
                """
                package p;

                public class Host {

                  private Base base;

                  public Host() {}

                  public void m() {
                    class Worker extends Base implements Runnable {
                      public void run() {}
                    }
                  }
                }
                """,
                "src/main/java/p/Base.java",
                "package p;\n\npublic class Base {\n\n  public Base() {}\n}\n"));

    assertThat(run.sink().tuples()).containsExactly("p.Host -> p.Base FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void construtorEMembrosImplicitosNaoDaoLinha() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Plain.java",
                "package p;\n\npublic class Plain {\n\n  public Plain() {}\n}\n",
                "src/main/java/p/Bare.java",
                "package p;\n\npublic class Bare {}\n",
                "src/main/java/p/Empty.java",
                "package p;\n\ninterface Empty {}\n",
                "src/main/java/p/Control.java",
                """
                package p;

                public class Control {

                  private Plain plain;

                  public Control() {}
                }
                """));

    assertThat(run.sink().tuples()).containsExactly("p.Control -> p.Plain FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void linhaDoTipoParaEleMesmoFica() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Node.java",
                """
                package p;

                public class Node {

                  private Node next;

                  public Node() {}

                  public int depth() {
                    return next == null ? 0 : 1 + next.depth();
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Node -> p.Node CALLS x1 RESOLVED", "p.Node -> p.Node FIELD_TYPE x1 RESOLVED");
  }

  @Test
  void lambdaAnonimaETipoMembroTemAtribuicoesDiferentes() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Host.java",
                """
                package p;

                public class Host {

                  private final Engine engine;

                  public Host(Engine engine) {
                    this.engine = engine;
                  }

                  public Runnable viaLambda() {
                    return () -> engine.start();
                  }

                  public Runnable viaAnonymous() {
                    return new Runnable() {
                      @Override
                      public void run() {
                        engine.stop();
                      }
                    };
                  }

                  public static final class Part {

                    public Part() {}

                    public void spin(Engine other) {
                      other.start();
                    }
                  }
                }
                """,
                "src/main/java/p/Engine.java",
                ENGINE));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Host -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Host -> java.lang.Runnable CALLS x1 RESOLVED external",
            "p.Host -> java.lang.Runnable RETURN_TYPE x2 RESOLVED external",
            "p.Host -> p.Engine CALLS x2 RESOLVED",
            "p.Host -> p.Engine FIELD_TYPE x1 RESOLVED",
            "p.Host -> p.Engine PARAMETER_TYPE x1 RESOLVED",
            "p.Host$Part -> p.Engine CALLS x1 RESOLVED",
            "p.Host$Part -> p.Engine PARAMETER_TYPE x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Host", "p.Engine", "CALLS")))
        .containsExactly("L12 method:p.Host#viaLambda()", "L19 method:p.Host#run()");
    assertThat(evidence(run.sink().row("p.Host", "java.lang.Runnable", "CALLS")))
        .containsExactly("L16 method:p.Host#viaAnonymous()");
    assertThat(evidence(run.sink().row("p.Host$Part", "p.Engine", "CALLS")))
        .containsExactly("L29 method:p.Host$Part#spin(p.Engine)");
  }

  @Test
  void oFromMethodDeInicializadoresSobeAteUmMetodoElementoOuFicaNulo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Wiring.java",
                """
                package p;

                public class Wiring {

                  private static final Engine SHARED;

                  static {
                    SHARED = Factory.create();
                  }

                  private final Engine engine = new Engine();

                  {
                    Factory.touch();
                  }

                  public Wiring() {}

                  public Object anonymous() {
                    return new Object() {
                      {
                        Factory.touch();
                      }
                    };
                  }
                }
                """,
                "src/main/java/p/Factory.java",
                """
                package p;

                public final class Factory {

                  private Factory() {}

                  public static Engine create() {
                    return new Engine();
                  }

                  public static void touch() {}
                }
                """,
                "src/main/java/p/Engine.java",
                ENGINE));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Factory -> p.Engine CALLS x1 RESOLVED",
            "p.Factory -> p.Engine RETURN_TYPE x1 RESOLVED",
            "p.Wiring -> java.lang.Object CALLS x1 RESOLVED external",
            "p.Wiring -> java.lang.Object RETURN_TYPE x1 RESOLVED external",
            "p.Wiring -> p.Engine CALLS x1 RESOLVED",
            "p.Wiring -> p.Engine FIELD_TYPE x2 RESOLVED",
            "p.Wiring -> p.Factory CALLS x3 RESOLVED");
    assertThat(evidence(run.sink().row("p.Wiring", "p.Factory", "CALLS")))
        .containsExactly(
            "L8 method:p.Wiring#<clinit>()", "L14 -", "L22 method:p.Wiring#anonymous()");
    assertThat(evidence(run.sink().row("p.Wiring", "p.Engine", "CALLS"))).containsExactly("L11 -");
    assertThat(evidence(run.sink().row("p.Wiring", "java.lang.Object", "CALLS")))
        .containsExactly("L20 method:p.Wiring#anonymous()");
  }

  @Test
  void superEThisExplicitosContamEOImplicitoNao() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Base.java",
                """
                package p;

                public class Base {

                  public Base() {}

                  public Base(int seed) {}

                  public void hook() {}
                }
                """,
                "src/main/java/p/Derived.java",
                """
                package p;

                public class Derived extends Base {

                  public Derived() {}

                  public Derived(int seed) {
                    super(seed);
                  }

                  public Derived(String label) {
                    this();
                  }

                  @Override
                  public void hook() {
                    super.hook();
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Derived -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Derived -> java.lang.String PARAMETER_TYPE x1 RESOLVED external",
            "p.Derived -> p.Base EXTENDS x1 RESOLVED",
            "p.Derived -> p.Base CALLS x2 RESOLVED",
            "p.Derived -> p.Derived CALLS x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Derived", "p.Base", "CALLS")))
        .containsExactly("L8 method:p.Derived#<init>(int)", "L17 method:p.Derived#hook()");
    assertThat(evidence(run.sink().row("p.Derived", "p.Derived", "CALLS")))
        .containsExactly("L12 method:p.Derived#<init>(java.lang.String)");
  }

  @Test
  void oAlvoDeUmaChamadaEOTipoQualificador() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Base.java",
                """
                package p;

                public class Base {

                  public Base() {}

                  public void inherited() {}
                }
                """,
                "src/main/java/p/Mid.java",
                "package p;\n\npublic class Mid extends Base {\n\n  public Mid() {}\n}\n",
                "src/main/java/p/Sub.java",
                """
                package p;

                public class Sub extends Mid {

                  public Sub() {}

                  public void own() {
                    inherited();
                  }

                  public void up() {
                    super.inherited();
                  }

                  public Runnable task() {
                    return new Runnable() {
                      @Override
                      public void run() {
                        own();
                      }
                    };
                  }
                }
                """,
                "src/main/java/p/Client.java",
                """
                package p;

                public class Client {

                  private final Sub sub;

                  public Client(Sub sub) {
                    this.sub = sub;
                  }

                  public void go() {
                    sub.inherited();
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Client -> p.Sub CALLS x1 RESOLVED",
            "p.Client -> p.Sub FIELD_TYPE x1 RESOLVED",
            "p.Client -> p.Sub PARAMETER_TYPE x1 RESOLVED",
            "p.Mid -> p.Base EXTENDS x1 RESOLVED",
            "p.Sub -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Sub -> java.lang.Runnable CALLS x1 RESOLVED external",
            "p.Sub -> java.lang.Runnable RETURN_TYPE x1 RESOLVED external",
            "p.Sub -> p.Mid EXTENDS x1 RESOLVED",
            "p.Sub -> p.Mid CALLS x1 RESOLVED",
            "p.Sub -> p.Sub CALLS x2 RESOLVED");
    assertThat(evidence(run.sink().row("p.Sub", "p.Sub", "CALLS")))
        .containsExactly("L8 method:p.Sub#own()", "L19 method:p.Sub#run()");
  }

  @Test
  void chamadaNaAnonimaAMembroHerdadoDeOutraRaizCortaNoDolar() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "core/src/main/java/a/base/Base.java",
                """
                package a.base;

                public class Base {

                  public Base() {}

                  public void inherited() {}
                }
                """,
                "app/src/main/java/a/app/Sub.java",
                """
                package a.app;

                import a.base.Base;

                public class Sub extends Base {

                  public Sub() {}

                  public Runnable task() {
                    return new Runnable() {
                      @Override
                      public void run() {
                        inherited();
                      }
                    };
                  }
                }
                """));

    assertThat(run.roots()).hasSize(2);
    assertThat(run.sink().tuples())
        .containsExactly(
            "a.app.Sub -> a.app.Sub CALLS x1 RESOLVED",
            "a.app.Sub -> a.base.Base IMPORTS x1 INFERRED",
            "a.app.Sub -> a.base.Base EXTENDS x1 INFERRED",
            "a.app.Sub -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "a.app.Sub -> java.lang.Runnable CALLS x1 RESOLVED external",
            "a.app.Sub -> java.lang.Runnable RETURN_TYPE x1 RESOLVED external");
    assertThat(evidence(run.sink().row("a.app.Sub", "a.app.Sub", "CALLS")))
        .containsExactly("L13 method:a.app.Sub#run()");
  }

  @Test
  void umNomeComPontosResolveOPrimeiroSegmentoPeloImportDeTipoUnico() throws Exception {
    final String client =
        """
        package b;

        import a.Api;

        public class Client {

          private Api.Inner inner;

          public Client() {}
        }
        """;
    final String remote =
        """
        package b;

        import com.x.Api;

        public class Remote {

          private Api.Inner inner;

          public Remote() {}
        }
        """;
    final String api =
        """
        package a;

        public class Api {

          public Api() {}

          public static class Inner {

            public Inner() {}
          }
        }
        """;

    final Extraction oneRoot =
        extract(
            "one-root",
            Map.of(
                "src/main/java/b/Client.java", client,
                "src/main/java/b/Remote.java", remote,
                "src/main/java/a/Api.java", api));
    final Extraction twoRoots =
        extract(
            "two-roots",
            Map.of(
                "src/main/java/b/Client.java", client,
                "src/main/java/b/Remote.java", remote,
                "src/test/java/a/Api.java", api));

    assertThat(oneRoot.sink().tuples())
        .containsExactly(
            "b.Client -> a.Api IMPORTS x1 RESOLVED",
            "b.Client -> a.Api$Inner FIELD_TYPE x1 RESOLVED",
            "b.Remote -> com.x.Api IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "b.Remote -> com.x.Api$Inner FIELD_TYPE x1 UNRESOLVED_EXTERNAL external");
    assertThat(twoRoots.sink().tuples())
        .containsExactly(
            "b.Client -> a.Api IMPORTS x1 INFERRED",
            "b.Client -> a.Api$Inner FIELD_TYPE x1 INFERRED",
            "b.Remote -> com.x.Api IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "b.Remote -> com.x.Api$Inner FIELD_TYPE x1 UNRESOLVED_EXTERNAL external");
  }

  @Test
  void cadaSitioDeUmaCadeiaConta() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Report.java",
                """
                package p;

                public class Report {

                  private final Repo repo;

                  public Report(Repo repo) {
                    this.repo = repo;
                  }

                  public int sum() {
                    return new Order().total() + repo.find().total();
                  }
                }
                """,
                "src/main/java/p/Repo.java",
                """
                package p;

                public class Repo {

                  public Repo() {}

                  public Order find() {
                    return new Order();
                  }
                }
                """,
                "src/main/java/p/Order.java",
                ORDER_WITH_TOTAL));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Repo -> p.Order CALLS x1 RESOLVED",
            "p.Repo -> p.Order RETURN_TYPE x1 RESOLVED",
            "p.Report -> p.Order CALLS x3 RESOLVED",
            "p.Report -> p.Repo CALLS x1 RESOLVED",
            "p.Report -> p.Repo FIELD_TYPE x1 RESOLVED",
            "p.Report -> p.Repo PARAMETER_TYPE x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Report", "p.Order", "CALLS")))
        .containsExactly(
            "L12 method:p.Report#sum()", "L12 method:p.Report#sum()", "L12 method:p.Report#sum()");
  }

  @Test
  void chamadasAoJdkSaoResolvidasEExternas() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Names.java",
                """
                package p;

                import java.util.ArrayList;
                import java.util.List;

                public class Names {

                  private final List<String> names = new ArrayList<>();

                  public Names() {}

                  public void add(int value) {
                    names.add(String.valueOf(value));
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Names -> java.lang.String CALLS x1 RESOLVED external",
            "p.Names -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "p.Names -> java.util.ArrayList IMPORTS x1 RESOLVED external",
            "p.Names -> java.util.ArrayList CALLS x1 RESOLVED external",
            "p.Names -> java.util.List IMPORTS x1 RESOLVED external",
            "p.Names -> java.util.List CALLS x1 RESOLVED external",
            "p.Names -> java.util.List FIELD_TYPE x1 RESOLVED external");
  }

  @Test
  void referenciaDeMetodoNaoEChamada() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Totals.java",
                """
                package p;

                import java.util.function.Supplier;
                import java.util.function.ToIntFunction;

                public class Totals {

                  public Totals() {}

                  public ToIntFunction<Order> reader() {
                    return Order::total;
                  }

                  public Supplier<Order> maker() {
                    return Order::new;
                  }
                }
                """,
                "src/main/java/p/Order.java",
                ORDER_WITH_TOTAL));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Totals -> java.util.function.Supplier IMPORTS x1 RESOLVED external",
            "p.Totals -> java.util.function.Supplier RETURN_TYPE x1 RESOLVED external",
            "p.Totals -> java.util.function.ToIntFunction IMPORTS x1 RESOLVED external",
            "p.Totals -> java.util.function.ToIntFunction RETURN_TYPE x1 RESOLVED external",
            "p.Totals -> p.Order RETURN_TYPE x2 RESOLVED");
  }

  @Test
  void alvoLocalOuAnonimoNaoViraLinha() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Shop.java",
                """
                package p;

                public class Shop {

                  public Shop() {}

                  public Object make() {
                    class Local {
                      Local() {}

                      void ping() {}
                    }
                    Local made = new Local();
                    made.ping();
                    Runnable task =
                        new Runnable() {
                          @Override
                          public void run() {}
                        };
                    task.run();
                    return made;
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Shop -> java.lang.Object RETURN_TYPE x1 RESOLVED external",
            "p.Shop -> java.lang.Override ANNOTATION_REFERENCE x1 RESOLVED external",
            "p.Shop -> java.lang.Runnable CALLS x2 RESOLVED external",
            "p.Shop -> p.Shop CALLS x1 RESOLVED");
  }

  @Test
  void moduleInfoDaLinhasDeModuloENenhumaDeTipo() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/module-info.java",
                """
                import p.Plugin;

                @Deprecated
                module m.app {
                  requires java.logging;
                  requires transitive java.sql;
                  requires static java.compiler;

                  exports p;

                  uses Plugin;

                  provides Plugin with
                      p.DefaultPlugin;
                }
                """,
                "src/main/java/p/Plugin.java",
                "package p;\n\npublic interface Plugin {\n\n  void load();\n}\n",
                "src/main/java/p/DefaultPlugin.java",
                """
                package p;

                public class DefaultPlugin implements Plugin {

                  public DefaultPlugin() {}

                  public void load() {}
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly("p.DefaultPlugin -> p.Plugin IMPLEMENTS x1 RESOLVED");
    assertThat(run.sink().modules())
        .extracting(RecordingStructureSink::module)
        .containsExactly(
            "m.app -> java.logging L5 src/main/java/module-info.java",
            "m.app -> java.sql transitive L6 src/main/java/module-info.java",
            "m.app -> java.compiler static L7 src/main/java/module-info.java");
    assertThat(run.analysis().files())
        .filteredOn(file -> file.relativePath().equals("src/main/java/module-info.java"))
        .extracting(SourceFile::status)
        .containsExactly(SourceFileStatus.ANALYZED);
  }

  @Test
  void aQualidadeVemDaRegraDeTexto() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "core/src/main/java/a/Api.java",
                "package a;\n\npublic class Api {\n\n  public Api() {}\n}\n",
                "app/src/main/java/b/Peer.java",
                "package b;\n\npublic class Peer {\n\n  public Peer() {}\n}\n",
                "app/src/main/java/b/User.java",
                """
                package b;

                import a.Api;
                import com.thirdparty.Missing;
                import java.util.List;

                public class User {

                  private Api api;
                  private Peer peer;
                  private Missing missing;
                  private List<String> names;

                  public User() {}
                }
                """));

    assertThat(run.roots()).hasSize(2);
    assertThat(run.sink().tuples())
        .containsExactly(
            "b.User -> a.Api IMPORTS x1 INFERRED",
            "b.User -> a.Api FIELD_TYPE x1 INFERRED",
            "b.User -> b.Peer FIELD_TYPE x1 RESOLVED",
            "b.User -> com.thirdparty.Missing IMPORTS x1 UNRESOLVED_EXTERNAL external",
            "b.User -> com.thirdparty.Missing FIELD_TYPE x1 UNRESOLVED_EXTERNAL external",
            "b.User -> java.lang.String FIELD_TYPE x1 RESOLVED external",
            "b.User -> java.util.List IMPORTS x1 RESOLVED external",
            "b.User -> java.util.List FIELD_TYPE x1 RESOLVED external");
    for (final ExtractedDependency row : run.sink().rows()) {
      assertThat(row.evidence())
          .as(tuple(row))
          .extracting(DependencyEvidence::resolution)
          .containsOnly(row.dependency().resolution());
    }
  }

  @Test
  void nomeSimplesSemCandidatoViraNuEOPacotePadraoEDescartado() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/Bootstrap.java",
                "public class Bootstrap {\n\n  public Bootstrap() {}\n}\n",
                "src/main/java/p/User.java",
                """
                package p;

                public class User {

                  private Ghost ghost;
                  private Bootstrap boot;

                  public User() {}
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly("p.User -> Ghost FIELD_TYPE x1 UNRESOLVED_EXTERNAL external");
  }

  @Test
  void perdedorDeRepeticaoNaoContribuiLinhas() throws Exception {
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Dup.java",
                """
                package p;

                public class Dup {

                  private Order order;

                  public Dup() {}
                }
                """,
                "src/main/java/p/Order.java",
                ORDER,
                "src/test/java/p/Dup.java",
                """
                package p;

                public class Dup {

                  private java.util.List<String> names;

                  public Dup() {}
                }
                """));

    assertThat(run.sink().tuples()).containsExactly("p.Dup -> p.Order FIELD_TYPE x1 RESOLVED");
    assertThat(run.sink().row("p.Dup", "p.Order", "FIELD_TYPE").filePath())
        .isEqualTo("src/main/java/p/Dup.java");
  }

  @Test
  void umaLinhaCom25SitiosGuarda20EvidenciasEConta25() throws Exception {
    final String calls =
        IntStream.range(0, 25).mapToObj(i -> "    engine.start();\n").collect(Collectors.joining());
    final Extraction run =
        extract(
            Map.of(
                "src/main/java/p/Chatty.java",
                """
                package p;

                public class Chatty {

                  private final Engine engine;

                  public Chatty(Engine engine) {
                    this.engine = engine;
                  }

                  public void burst() {
                """
                    + calls
                    + "  }\n}\n",
                "src/main/java/p/Engine.java",
                ENGINE));

    assertThat(run.sink().tuples())
        .containsExactly(
            "p.Chatty -> p.Engine CALLS x25 RESOLVED",
            "p.Chatty -> p.Engine FIELD_TYPE x1 RESOLVED",
            "p.Chatty -> p.Engine PARAMETER_TYPE x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Chatty", "p.Engine", "CALLS")))
        .containsExactlyElementsOf(
            IntStream.rangeClosed(12, 31)
                .mapToObj(line -> "L" + line + " method:p.Chatty#burst()")
                .toList());
  }

  private record Extraction(
      List<SourceRoot> roots, SourceAnalysis analysis, RecordingStructureSink sink) {}

  private Extraction extract(Map<String, String> files) throws Exception {
    return extract("repository", files);
  }

  private Extraction extract(String directory, Map<String, String> files) throws Exception {
    return extract(SourceTrees.repository(temp.resolve(directory), files));
  }

  private static Extraction extract(ValidatedRepository repository) {
    final List<SourceRoot> roots = inVisitOrder(repository);
    final RecordingStructureSink sink = new RecordingStructureSink();
    final StructureExtractor extractor = new StructureExtractor(roots, sink);
    final SourceAnalysis analysis =
        new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    final Extraction run = new Extraction(roots, analysis, sink);
    invariantesDaEvidencia(run);
    return run;
  }

  private static void invariantesDaEvidencia(Extraction run) {
    for (final ExtractedDependency row : run.sink().dependencies()) {
      final String owner = "method:" + row.dependency().source().sortKey() + "#";
      for (final DependencyEvidence entry : row.evidence()) {
        if (entry.fromMethod() != null) {
          assertThat(entry.fromMethod().value()).as(tuple(row)).startsWith(owner);
        }
        assertThat(entry.toMethod()).as(tuple(row) + ": toMethod fica nulo").isNull();
      }
    }
  }

  private static List<SourceRoot> inVisitOrder(ValidatedRepository repository) {
    return repository.layout().sourceRoots().stream()
        .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
        .toList();
  }

  private static Map<String, String> staticImportCase(String staticImport) {
    return Map.of(
        "src/main/java/p/Client.java",
        """
        package p;

        %s

        public class Client {

          public Client() {}

          public void run() {
            helper();
          }
        }
        """
            .formatted(staticImport),
        "src/main/java/q/Util.java",
        UTIL);
  }

  private static JsonNode golden(String fixture) throws IOException {
    return JSON.readTree(TestRepositories.corpus(fixture).resolve("expected.json").toFile());
  }

  private static List<String> goldenTuples(JsonNode golden) {
    final List<String> tuples = new ArrayList<>();
    for (final JsonNode row : golden.get("dependencies")) {
      tuples.add(
          bare(row.get("from").asText())
              + " -> "
              + bare(row.get("to").asText())
              + " "
              + row.get("type").asText()
              + " x"
              + row.get("occurrences").asInt()
              + " "
              + row.get("resolution").asText()
              + (row.path("external").asBoolean(false) ? " external" : ""));
    }
    return List.copyOf(tuples);
  }

  private static String head(ExtractedDependency row) {
    return row.dependency().source().sortKey()
        + " -> "
        + row.dependency().target().sortKey()
        + " "
        + row.dependency().type();
  }

  private static String bare(String typeId) {
    return new CodeElementId(typeId).sortKey();
  }
}
