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
                Map.entry("MAX_GIT_ALTERNATES_DEPTH", 5),
                Map.entry("MAX_LOOSE_OBJECTS", 200_000),
                Map.entry("MAX_PACK_INDEX_BYTES", 268_435_456L),
                Map.entry("MAX_PACK_INDEX_TOTAL_BYTES", 536_870_912L),
                Map.entry("MAX_COMMIT_GRAPH_BYTES", 268_435_456L)));
  }
}
