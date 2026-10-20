package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.tuple;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ResolutionHarness {

  static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<String> VALID_PAIRS =
      List.of("RESOLVED false", "INFERRED false", "RESOLVED true", "UNRESOLVED_EXTERNAL true");

  private ResolutionHarness() {}

  enum Layout {
    ONE_ROOT("uma raiz", 1),
    TWO_ROOTS("duas raízes", 2);

    private final String label;
    private final int roots;

    Layout(String label, int roots) {
      this.label = label;
      this.roots = roots;
    }

    int roots() {
      return roots;
    }

    Map<String, String> place(Map<String, String> core, Map<String, String> app) {
      final Map<String, String> files = new LinkedHashMap<>();
      final String corePrefix = this == ONE_ROOT ? "src/main/java/" : "core/src/main/java/";
      final String appPrefix = this == ONE_ROOT ? "src/main/java/" : "app/src/main/java/";
      core.forEach((path, source) -> files.put(corePrefix + path, source));
      app.forEach((path, source) -> files.put(appPrefix + path, source));
      return files;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  record Extraction(
      List<SourceRoot> roots,
      SourceAnalysis analysis,
      StructureExtractor extractor,
      RecordingStructureSink sink) {

    Map<DropReason, Long> drops() {
      final Map<DropReason, Long> nonZero = new EnumMap<>(DropReason.class);
      extractor
          .droppedOccurrences()
          .forEach(
              (reason, count) -> {
                if (count != 0) {
                  nonZero.put(reason, count);
                }
              });
      return nonZero;
    }
  }

  static Extraction extract(
      Path directory, Layout layout, Map<String, String> core, Map<String, String> app)
      throws Exception {
    final Extraction run = extract(SourceTrees.repository(directory, layout.place(core, app)));
    assertThat(run.roots()).as("pré-condição: as raízes de " + layout).hasSize(layout.roots());
    return run;
  }

  static Extraction extract(ValidatedRepository repository) {
    final List<SourceRoot> roots = inVisitOrder(repository);
    final RecordingStructureSink sink = new RecordingStructureSink();
    final StructureExtractor extractor = new StructureExtractor(roots, sink);
    final SourceAnalysis analysis =
        new SpoonModelBuilder().analyze(repository, roots, extractor, SourceTrees.NEVER);
    final Extraction run = new Extraction(roots, analysis, extractor, sink);
    invariants(run);
    return run;
  }

  static List<String> goldenTuples(String fixture) throws IOException {
    final List<String> tuples = new ArrayList<>();
    for (final JsonNode row : golden(fixture).get("dependencies")) {
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
              + (row.path("external").asBoolean(false) ? " external" : "")
              + (row.path("ambiguous").asBoolean(false) ? " ambiguous" : ""));
    }
    return List.copyOf(tuples);
  }

  static List<String> goldenTypes(String fixture) throws IOException {
    final List<String> ids = new ArrayList<>();
    for (final JsonNode row : golden(fixture).get("types")) {
      ids.add(row.get("id").asText());
    }
    return List.copyOf(ids);
  }

  private static JsonNode golden(String fixture) throws IOException {
    return JSON.readTree(TestRepositories.corpus(fixture).resolve("expected.json").toFile());
  }

  private static String bare(String typeId) {
    return new CodeElementId(typeId).sortKey();
  }

  private static List<SourceRoot> inVisitOrder(ValidatedRepository repository) {
    return repository.layout().sourceRoots().stream()
        .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
        .toList();
  }

  private static void invariants(Extraction run) {
    for (final ExtractedDependency row : run.sink().dependencies()) {
      final ResolutionQuality resolution = row.dependency().resolution();
      assertThat(resolution + " " + !row.targetDeclared())
          .as(tuple(row) + ": um dos quatro pares válidos")
          .isIn(VALID_PAIRS);
      if (row.ambiguous()) {
        assertThat(resolution).as(tuple(row)).isEqualTo(ResolutionQuality.UNRESOLVED_EXTERNAL);
        assertThat(row.targetDeclared()).as(tuple(row) + ": ambígua é externa").isFalse();
        assertThat(row.dependency().target().sortKey())
            .as(tuple(row) + ": ambígua é o nome nu, nunca um palpite")
            .doesNotContain(".");
      }
      final String owner = "method:" + row.dependency().source().sortKey() + "#";
      for (final DependencyEvidence entry : row.evidence()) {
        if (entry.fromMethod() != null) {
          assertThat(entry.fromMethod().value()).as(tuple(row)).startsWith(owner);
        }
        if (entry.toMethod() != null) {
          assertThat(row.dependency().type())
              .as(tuple(row) + ": toMethod só em uma chamada")
              .isEqualTo(DependencyType.CALLS);
          assertThat(entry.toMethod().kind()).as(tuple(row)).isEqualTo(ElementKind.METHOD);
        }
      }
    }
  }
}
