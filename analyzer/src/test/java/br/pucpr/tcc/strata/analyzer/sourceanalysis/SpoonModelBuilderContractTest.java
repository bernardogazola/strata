package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import br.pucpr.tcc.strata.analyzer.repository.HeadState;
import br.pucpr.tcc.strata.analyzer.repository.ProjectLayout;
import br.pucpr.tcc.strata.analyzer.repository.RepositorySnapshot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;

final class SpoonModelBuilderContractTest {

  private static final String CATALOG = "core/src/main/java/br/pucpr/legacy/core/Catalog.java";

  private static final int MAX_GC_ROUNDS = 50;

  private static final long GC_PAUSE_MILLIS = 20;

  @TempDir private Path temp;

  @Test
  void comSoARaizDeForaSelecionadaOsArquivosDaDeDentroNaoSaoAtribuidos() throws Exception {
    final ValidatedRepository repository = nested(2);
    final SourceRoot outer = root(repository, "src");
    final List<String> modeled = new ArrayList<>();

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(
                repository,
                List.of(outer),
                batch -> modeled.addAll(batch.files()),
                SourceTrees.NEVER);

    assertThat(analysis.totalFiles()).isEqualTo(outer.javaFileCount()).isEqualTo(2);
    assertThat(analysis.files())
        .extracting(SourceFile::relativePath)
        .containsExactlyInAnyOrder("src/p/Outer.java", "src/p/Other.java");
    assertThat(modeled).containsExactlyInAnyOrder("src/p/Outer.java", "src/p/Other.java");
  }

  @Test
  void comAsDuasSelecionadasCadaArquivoFicaNaSuaRaizMaisFunda() throws Exception {
    final ValidatedRepository repository = nested(2);
    final SourceRoot outer = root(repository, "src");
    final SourceRoot inner = root(repository, "src/gen");

    final SourceAnalysis analysis =
        new SpoonModelBuilder()
            .analyze(repository, List.of(outer, inner), batch -> {}, SourceTrees.NEVER);

    final Map<String, SourceRootId> owners = new TreeMap<>();
    analysis.files().forEach(file -> owners.put(file.relativePath(), file.rootId()));
    assertThat(owners)
        .containsExactlyEntriesOf(
            new TreeMap<>(
                Map.of(
                    "src/gen/q/Generated.java", inner.id(),
                    "src/p/Other.java", outer.id(),
                    "src/p/Outer.java", outer.id())));
  }

  @Test
  void umaRaizCujaContagemNaoFechaComAAtribuicaoERecusada() throws Exception {
    final ValidatedRepository repository = nested(3);
    final List<SourceRoot> both = repository.layout().sourceRoots();

    assertThatThrownBy(
            () -> new SpoonModelBuilder().analyze(repository, both, batch -> {}, SourceTrees.NEVER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMesmaRaizSelecionadaDuasVezesERecusada() throws Exception {
    final ValidatedRepository repository = nested(2);
    final SourceRoot outer = root(repository, "src");

    assertThatThrownBy(
            () ->
                new SpoonModelBuilder()
                    .analyze(repository, List.of(outer, outer), batch -> {}, SourceTrees.NEVER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void umaRaizQueNaoEDoRepositorioERecusada() throws Exception {
    final ValidatedRepository repository = nested(2);
    final SourceRoot stranger =
        new SourceRoot(SourceRootId.derive("lib/src"), "lib/src", SourceRootKind.MAIN, 1);

    assertThatThrownBy(
            () ->
                new SpoonModelBuilder()
                    .analyze(repository, List.of(stranger), batch -> {}, SourceTrees.NEVER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oPrimeiroLoteJaTemAsDeclaracoesDeUmaRaizPosterior() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.fixture("multi-module", temp.toRealPath().resolve("multi-module"));
    final SourceRoot api = root(repository, "api/src/main/java");
    final SourceRoot core = root(repository, "core/src/main/java");
    final List<String> visitedRoots = new ArrayList<>();
    final List<SourceFile> seenByFirstBatch = new ArrayList<>();

    new SpoonModelBuilder()
        .analyze(
            repository,
            List.of(api, core),
            batch -> {
              if (visitedRoots.isEmpty()) {
                seenByFirstBatch.addAll(batch.analysisFiles());
              }
              visitedRoots.add(batch.root().relativePath());
            },
            SourceTrees.NEVER);

    assertThat(visitedRoots).first().isEqualTo("api/src/main/java");
    assertThat(seenByFirstBatch)
        .filteredOn(file -> file.relativePath().equals(CATALOG))
        .singleElement()
        .satisfies(
            catalog -> {
              assertThat(catalog.rootId()).isEqualTo(core.id());
              assertThat(catalog.declarations())
                  .isEqualTo(
                      new SourceFile.Declarations("br.pucpr.legacy.core", List.of("Catalog")));
            });
    assertThat(seenByFirstBatch)
        .extracting(SourceFile::relativePath)
        .containsExactlyInAnyOrder(
            "api/src/main/java/br/pucpr/legacy/api/CatalogEndpoint.java",
            "api/src/main/java/br/pucpr/legacy/api/Main.java",
            "core/src/main/java/br/pucpr/legacy/core/Audited.java",
            CATALOG,
            "core/src/main/java/br/pucpr/legacy/core/CatalogEntry.java",
            "core/src/main/java/br/pucpr/legacy/core/Kind.java",
            "core/src/main/java/br/pucpr/legacy/core/spi/MemoryStore.java",
            "core/src/main/java/br/pucpr/legacy/core/spi/Store.java");
  }

  @Test
  void oLoteFechadoSoltaOModeloMesmoGuardadoPeloVisitante() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.fixture("multi-module", temp.toRealPath().resolve("multi-module"));
    final List<ModeledBatch> leaked = new ArrayList<>();
    final List<WeakReference<CtModel>> models = new ArrayList<>();

    new SpoonModelBuilder()
        .analyze(
            repository,
            repository.layout().sourceRoots(),
            batch -> {
              leaked.add(batch);
              models.add(new WeakReference<>(batch.model()));
            },
            SourceTrees.NEVER);

    assertThat(models).isNotEmpty();
    assertThat(collected(models))
        .as("cada modelo coletado com o lote ainda alcançável: close() soltou a referência")
        .isTrue();
    assertThat(leaked).hasSameSizeAs(models);
  }

  private ValidatedRepository nested(int outerCount) throws Exception {
    final Path directory = temp.toRealPath().resolve("nested");
    TestRepositories.write(directory, "src/p/Outer.java", "package p;\n\nclass Outer {}\n");
    TestRepositories.write(directory, "src/p/Other.java", "package p;\n\nclass Other {}\n");
    TestRepositories.write(
        directory, "src/gen/q/Generated.java", "package q;\n\nclass Generated {}\n");
    final List<SourceRoot> roots =
        List.of(
            new SourceRoot(SourceRootId.derive("src"), "src", SourceRootKind.MAIN, outerCount),
            new SourceRoot(SourceRootId.derive("src/gen"), "src/gen", SourceRootKind.GENERATED, 1));
    return new ValidatedRepository(
        new RepositorySnapshot(
            directory,
            "nested",
            new HeadState(null, "main", false),
            TestRepositories.inventoryOf(directory)),
        new ProjectLayout(BuildSystem.NONE, List.of(), roots));
  }

  private static SourceRoot root(ValidatedRepository repository, String relativePath) {
    return repository.layout().sourceRoots().stream()
        .filter(root -> root.relativePath().equals(relativePath))
        .findFirst()
        .orElseThrow();
  }

  private static boolean collected(List<WeakReference<CtModel>> models)
      throws InterruptedException {
    for (int round = 0; round < MAX_GC_ROUNDS; round++) {
      if (models.stream().allMatch(model -> model.get() == null)) {
        return true;
      }
      System.gc();
      Thread.sleep(GC_PAUSE_MILLIS);
    }
    return models.stream().allMatch(model -> model.get() == null);
  }
}
