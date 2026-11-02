package br.pucpr.tcc.strata.analyzer.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class AnalysisConstantsTest {

  @Test
  void osLimitesDaFase27SaoEcoadosNosMetadados() {
    assertThat(AnalysisConstants.asMetadata())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("CPU_POOL_MAX_THREADS", 8),
                Map.entry("PROGRESS_THROTTLE_MILLIS", 100),
                Map.entry("RUN_STOP_WAIT_MILLIS", 2_500),
                Map.entry("WALK_CHECK_STRIDE_ENTRIES", 1_024),
                Map.entry("MAX_COMMITS_WALKED", 200_000),
                Map.entry("MAX_SOURCE_WARNINGS_PER_CODE", 100),
                Map.entry("MAX_GIT_ALTERNATES", 8),
                Map.entry("MAX_GIT_ALTERNATES_DEPTH", 6),
                Map.entry("MAX_OBJECT_STORE_ENTRIES", 200_000),
                Map.entry("MAX_PACK_INDEX_BYTES", 268_435_456L),
                Map.entry("MAX_PACK_INDEX_TOTAL_BYTES", 536_870_912L),
                Map.entry("MAX_COMMIT_GRAPH_BYTES", 268_435_456L)));
  }

  @Test
  void asConstantesDoGrafoDaFase30SaoEcoadasNosMetadados() {
    assertThat(AnalysisConstants.asMetadata())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("PAGERANK_DAMPING", 0.85d),
                Map.entry("PAGERANK_TOLERANCE", 1e-10d),
                Map.entry("PAGERANK_MAX_ITER", 200),
                Map.entry("BETWEENNESS_MAX_NODES", 1_000),
                Map.entry("BETWEENNESS_MAX_EDGES", 20_000),
                Map.entry("GRAPH_READ_CHECK_STRIDE_ROWS", 8_192)));
  }

  @Test
  void osLimitesDasLeiturasDaFase31SaoEcoadosNosMetadados() {
    assertThat(AnalysisConstants.asMetadata())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("GRAPH_SLICE_PACKAGE_MAX_NODES", 300),
                Map.entry("GRAPH_SLICE_PACKAGE_MAX_EDGES", 1_200),
                Map.entry("GRAPH_SLICE_TYPE_MAX_NODES", 1_000),
                Map.entry("GRAPH_SLICE_TYPE_MAX_EDGES", 5_000),
                Map.entry("OUTLINE_PAGE_DEFAULT", 200),
                Map.entry("OUTLINE_PAGE_MAX", 500),
                Map.entry("SEARCH_RESULTS_MAX", 50),
                Map.entry("MAX_CURSOR_CHARS", 256),
                Map.entry("MAX_SEARCH_QUERY_CODE_POINTS", 128)))
        .doesNotContainKeys(
            "GRAPH_SLICE_EXCEPTIONAL_MAX_NODES",
            "GRAPH_SLICE_EXCEPTIONAL_MAX_EDGES",
            "MAX_CURSOR_BYTES",
            "MAX_SEARCH_QUERY_CHARS");
  }

  @Test
  void osLimitesDasLeiturasDaFase32SaoEcoadosNosMetadados() {
    assertThat(AnalysisConstants.asMetadata())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("DEPENDENCY_PAGE_DEFAULT", 500),
                Map.entry("DEPENDENCY_PAGE_MAX", 2_000),
                Map.entry("DEPENDENCY_PAGE_MAX_BYTES", 4_194_304),
                Map.entry("DEPENDENCY_EVIDENCE_RESULTS_MAX", 50),
                Map.entry("ELEMENT_METHODS_MAX", 1_000),
                Map.entry("DEPENDENCY_EVIDENCE_CAP", 20)));
  }

  @Test
  void oTetoDeEntradasDoBancoDeObjetosSubstituiODeObjetosSoltos() {
    assertThat(AnalysisConstants.asMetadata()).doesNotContainKey("MAX_LOOSE_OBJECTS");
  }
}
