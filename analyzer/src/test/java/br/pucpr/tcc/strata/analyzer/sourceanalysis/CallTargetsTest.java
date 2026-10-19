package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CallTargetsTest {

  private static final String CLIENT_IMPORT = "import com.thirdparty.Client;\n";

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

  private record Run(StructureExtractor extractor, RecordingStructureSink sink) {

    Map<DropReason, Long> drops() {
      return extractor.droppedOccurrences();
    }
  }

  private Run extract(Map<String, String> files) throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(temp.resolve("repository"), files);
    final List<SourceRoot> roots = repository.layout().sourceRoots();
    final RecordingStructureSink sink = new RecordingStructureSink();
    final StructureExtractor extractor = new StructureExtractor(roots, sink);
    new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    return new Run(extractor, sink);
  }
}
