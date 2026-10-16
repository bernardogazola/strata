package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureIsolator.Attempt;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureIsolator.Isolation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

final class ParseFailureIsolatorTest {

  @TempDir private Path temp;

  @Test
  void umaListaQueConstroiEConstruidaUmaVezEEntregueInteira() {
    final FakeBuilder builder = new FakeBuilder(files(64), Set.of(), List.of());

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.builds()).isEqualTo(1);
    assertThat(isolation.failed()).isEmpty();
    assertThat(isolation.batches()).containsExactly(files(64));
    assertThat(builder.consumed).containsExactly(files(64));
  }

  @Test
  void umaListaVaziaNaoChamaOConstrutor() {
    final FakeBuilder builder = new FakeBuilder(List.of(), Set.of(), List.of());

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.builds()).isZero();
    assertThat(isolation.batches()).isEmpty();
    assertThat(isolation.failed()).isEmpty();
  }

  @Test
  void umArquivoRuimEntreMilEVinteEQuatroEAchadoEmConstrucoesLogaritmicas() {
    final List<String> files = files(1024);
    final String bad = files.get(700);
    final FakeBuilder builder = new FakeBuilder(files, Set.of(bad), List.of());

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).containsExactly(bad);
    assertThat(isolation.batches()).containsExactly(without(files, Set.of(bad)));
    assertThat(isolation.builds()).isLessThanOrEqualTo(2 * 10 + 3);
  }

  @Test
  void quarentaArquivosRuinsSaoAchadosSemUmaConstrucaoPorArquivo() {
    final List<String> files = files(1024);
    final Set<String> bad = new HashSet<>();
    IntStream.range(0, 40).forEach(index -> bad.add(files.get(index * 25 + 3)));
    final FakeBuilder builder = new FakeBuilder(files, bad, List.of());

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).containsExactlyElementsOf(inOrder(files, bad));
    assertThat(isolation.batches()).containsExactly(without(files, bad));
    assertThat(builder.volume).isLessThanOrEqualTo((10 + 3) * files.size());
  }

  @Test
  void todoArquivoRuimViraFalhaENenhumLoteEEntregue() {
    final List<String> files = files(16);
    final FakeBuilder builder = new FakeBuilder(files, Set.copyOf(files), List.of());

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).containsExactlyElementsOf(files);
    assertThat(isolation.batches()).isEmpty();
    assertThat(builder.consumed).isEmpty();
  }

  @Test
  void umParQueSoFalhaJuntoEmMetadesDiferentesESeparadoSemPerderArquivo() {
    final List<String> files = files(128);
    final List<String> pair = List.of(files.get(10), files.get(100));
    final FakeBuilder builder = new FakeBuilder(files, Set.of(), List.of(pair));

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).isEmpty();
    assertSplitsThePairWithAQuarantineOfOne(isolation, files, pair);
  }

  @Test
  void umParQueSoFalhaJuntoDentroDeUmaMesmaMetadeTambemEIsoladoComQuarentenaDeUm() {
    final List<String> files = files(128);
    final List<String> pair = List.of(files.get(3), files.get(5));
    final FakeBuilder builder = new FakeBuilder(files, Set.of(), List.of(pair));

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).isEmpty();
    assertSplitsThePairWithAQuarantineOfOne(isolation, files, pair);
  }

  @Test
  void umArquivoRuimEUmParNaMesmaListaSaoTratadosCadaUmPelaSuaRegra() {
    final List<String> files = files(200);
    final String bad = files.get(150);
    final List<String> pair = List.of(files.get(20), files.get(21));
    final FakeBuilder builder = new FakeBuilder(files, Set.of(bad), List.of(pair));

    final Isolation<String> isolation = builder.isolate();

    assertThat(isolation.failed()).containsExactly(bad);
    assertSplitsThePairWithAQuarantineOfOne(isolation, without(files, Set.of(bad)), pair);
  }

  @Test
  void todaListaDadaAoConstrutorPreservaAOrdemOriginal() {
    final List<String> files = files(300);
    final FakeBuilder builder =
        new FakeBuilder(
            files,
            Set.of(files.get(7), files.get(290)),
            List.of(List.of(files.get(40), files.get(260))));

    builder.isolate();

    for (final List<String> call : builder.calls) {
      final List<Integer> indexes = call.stream().map(files::indexOf).toList();
      assertThat(indexes).isSorted().doesNotHaveDuplicates();
    }
  }

  @Test
  void umCancelamentoLancadoPeloConstrutorSobeENadaMaisETentado() {
    final List<String> files = files(64);
    final FakeBuilder builder = new FakeBuilder(files, Set.of(files.get(1)), List.of());
    builder.cancelAtCall = 3;

    assertThatThrownBy(builder::isolate).isInstanceOf(CancellationException.class);
    assertThat(builder.calls).hasSize(3);
  }

  @Test
  void milListasAleatoriasRespeitamTodasAsInvariantes() {
    final Random random = new Random(19);
    for (int trial = 0; trial < 1_000; trial++) {
      final List<String> files = files(1 + random.nextInt(160));
      final Set<String> bad = new HashSet<>();
      final int badCount = random.nextInt(4);
      for (int index = 0; index < badCount; index++) {
        bad.add(files.get(random.nextInt(files.size())));
      }
      final List<List<String>> pairs = new ArrayList<>();
      final List<String> healthy = without(files, bad);
      final int pairCount = healthy.size() < 2 ? 0 : random.nextInt(3);
      for (int index = 0; index < pairCount; index++) {
        final String first = healthy.get(random.nextInt(healthy.size()));
        final String second = healthy.get(random.nextInt(healthy.size()));
        if (!first.equals(second)) {
          pairs.add(inOrder(files, Set.of(first, second)));
        }
      }
      final FakeBuilder builder = new FakeBuilder(files, bad, pairs);

      final Isolation<String> isolation = builder.isolate();

      final String scenario = "trial " + trial + " bad=" + bad + " pairs=" + pairs;
      assertThat(isolation.failed()).as(scenario).containsExactlyElementsOf(inOrder(files, bad));
      final List<String> everywhere = new ArrayList<>(isolation.failed());
      isolation.batches().forEach(everywhere::addAll);
      assertThat(everywhere).as(scenario).containsExactlyInAnyOrderElementsOf(files);
      assertThat(isolation.batches()).as(scenario).noneMatch(builder.fails);
      assertThat(builder.consumed).as(scenario).containsExactlyElementsOf(isolation.batches());
      assertThat(isolation.builds()).as(scenario).isEqualTo(builder.calls.size());
    }
  }

  @Test
  void brokenSourceIdentificaOArquivoRuimExcluiEleEAnalisaORestoComAviso() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.fixture("broken-source", temp.toRealPath().resolve("broken-source"));
    final SourceRoot root = repository.layout().sourceRoots().getFirst();
    final List<String> types = new ArrayList<>();
    final List<Boolean> neighbourResolvesInsideTheModel = new ArrayList<>();

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(
                repository,
                List.of(root),
                batch -> {
                  batch.model().getAllTypes().forEach(type -> types.add(type.getQualifiedName()));
                  final CtType<?> healthy =
                      batch.model().getAllTypes().stream()
                          .filter(type -> type.getSimpleName().equals("Healthy"))
                          .findFirst()
                          .orElseThrow();
                  final CtField<?> field = healthy.getField("neighbour");
                  final CtType<?> declaration = field.getType().getTypeDeclaration();
                  neighbourResolvesInsideTheModel.add(
                      declaration != null
                          && !declaration.isShadow()
                          && declaration.getQualifiedName().equals("br.pucpr.broken.Neighbour"));
                },
                SourceTrees.NEVER);

    final String bad = "src/main/java/br/pucpr/broken/ReportPath.java";
    assertThat(analysis.totalFiles()).isEqualTo(4);
    assertThat(analysis.parseFailures()).isEqualTo(1);
    assertThat(analysis.skippedFiles()).isEqualTo(1);
    assertThat(statuses(analysis))
        .containsEntry(bad, SourceFileStatus.PARSE_FAILED)
        .containsEntry("src/main/java/br/pucpr/broken/Healthy.java", SourceFileStatus.ANALYZED)
        .containsEntry("src/main/java/br/pucpr/broken/Neighbour.java", SourceFileStatus.ANALYZED)
        .containsEntry(
            "src/main/java/br/pucpr/broken/package-info.java", SourceFileStatus.ANALYZED);
    assertThat(causeOf(analysis, bad)).isEqualTo(ParseFailureCause.SYNTAX_ERROR);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(bad));
    assertThat(types)
        .containsExactlyInAnyOrder("br.pucpr.broken.Healthy", "br.pucpr.broken.Neighbour");
    assertThat(neighbourResolvesInsideTheModel)
        .as("Healthy → Neighbour FIELD_TYPE: o golden só fecha se o vizinho ficou no mesmo modelo")
        .containsExactly(true);
  }

  @Test
  void umArquivoCujoModeloLancaSozinhoEAchadoPorBissecaoEORestoContinua() throws Exception {
    final Map<String, String> files = new LinkedHashMap<>();
    for (int index = 0; index < 12; index++) {
      files.put(
          "src/main/java/p/Healthy" + index + ".java",
          "package p;\n\nfinal class Healthy" + index + " {\n  Pipeline pipeline;\n}\n");
    }
    final String crash = "src/main/java/p/Pipeline.java";
    files.put(
        crash,
        """
        package p;

        final class Pipeline {
          Object run(Unknown origin) {
            return origin.apply(item -> {
              final class Step implements Missing {}
              return new Step();
            });
          }
        }
        """);
    final ValidatedRepository repository = SourceTrees.repository(temp.resolve("crash"), files);

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(repository, repository.layout().sourceRoots(), batch -> {}, SourceTrees.NEVER);

    assertThat(analysis.parseFailures()).isEqualTo(1);
    assertThat(causeOf(analysis, crash)).isEqualTo(ParseFailureCause.MODEL_BUILD_CRASH);
    assertThat(analysis.issues()).containsExactly(SourceIssue.parseFailed(crash));
    assertThat(statuses(analysis).values())
        .filteredOn(status -> status == SourceFileStatus.ANALYZED)
        .hasSize(12);
    assertThat(analysis.diagnostics().builds()).isGreaterThan(1);
  }

  @Test
  void doisArquivosQueDeclaramOMesmoTipoVaoParaLotesSeparadosSemNenhumaFalha() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(
            temp.resolve("twins"),
            Map.of(
                "src/main/java/p/First.java", "package p;\n\nclass Twin {\n  int first;\n}\n",
                "src/main/java/p/Second.java", "package p;\n\nclass Twin {\n  int second;\n}\n",
                "src/main/java/p/User.java", "package p;\n\nclass User {\n  Twin twin;\n}\n"));
    final List<List<String>> batches = new ArrayList<>();

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(
                repository,
                repository.layout().sourceRoots(),
                batch -> batches.add(batch.files()),
                SourceTrees.NEVER);

    assertThat(analysis.parseFailures()).isZero();
    assertThat(analysis.issues()).isEmpty();
    assertThat(analysis.diagnostics().quarantinedBatches()).isEqualTo(1);
    assertThat(batches).hasSize(2);
    assertThat(batches.stream().flatMap(List::stream))
        .containsExactlyInAnyOrder(
            "src/main/java/p/First.java",
            "src/main/java/p/Second.java",
            "src/main/java/p/User.java");
  }

  private static void assertSplitsThePairWithAQuarantineOfOne(
      Isolation<String> isolation, List<String> survivors, List<String> pair) {
    assertThat(isolation.batches()).hasSize(2);
    assertThat(isolation.batches().get(1)).hasSize(1).containsAnyElementsOf(pair);
    assertThat(isolation.batches().getFirst())
        .doesNotContainAnyElementsOf(isolation.batches().get(1));
    final List<String> union = new ArrayList<>(isolation.batches().getFirst());
    union.addAll(isolation.batches().get(1));
    assertThat(union).containsExactlyInAnyOrderElementsOf(survivors);
    assertThat(isolation.batches()).noneMatch(batch -> batch.containsAll(pair));
  }

  private static List<String> files(int count) {
    return IntStream.range(0, count).mapToObj(index -> "f%04d".formatted(index)).toList();
  }

  private static List<String> without(List<String> files, Set<String> removed) {
    return files.stream().filter(file -> !removed.contains(file)).toList();
  }

  private static List<String> inOrder(List<String> files, Set<String> chosen) {
    return files.stream().filter(chosen::contains).toList();
  }

  private static Map<String, SourceFileStatus> statuses(SourceAnalysis analysis) {
    final Map<String, SourceFileStatus> statuses = new LinkedHashMap<>();
    analysis.files().forEach(file -> statuses.put(file.relativePath(), file.status()));
    return statuses;
  }

  private static ParseFailureCause causeOf(SourceAnalysis analysis, String relativePath) {
    return analysis.files().stream()
        .filter(file -> file.relativePath().equals(relativePath))
        .findFirst()
        .orElseThrow()
        .cause();
  }

  private static final class FakeBuilder {

    private final List<String> files;
    private final Predicate<List<String>> fails;
    private final List<List<String>> calls = new ArrayList<>();
    private final List<List<String>> consumed = new ArrayList<>();
    private int volume;
    private int cancelAtCall = -1;

    private FakeBuilder(List<String> files, Set<String> bad, List<List<String>> pairs) {
      this.files = files;
      this.fails =
          list ->
              list.stream().anyMatch(bad::contains)
                  || pairs.stream().anyMatch(pair -> list.containsAll(pair));
    }

    private Isolation<String> isolate() {
      final ParseFailureIsolator<String, List<String>> isolator =
          new ParseFailureIsolator<>(
              list -> {
                calls.add(List.copyOf(list));
                volume += list.size();
                if (calls.size() == cancelAtCall) {
                  throw new CancellationException("cancelado pelo teste");
                }
                return fails.test(list)
                    ? new Attempt.Failed<>(new IllegalStateException("falha"))
                    : new Attempt.Built<>(List.copyOf(list));
              });
      return isolator.isolate(
          files,
          (batch, model) -> {
            assertThat(model).isEqualTo(batch);
            consumed.add(List.copyOf(batch));
          });
    }
  }
}
