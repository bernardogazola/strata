package br.pucpr.tcc.strata.analyzer.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class AnalysisConstants {

  public static final int PROTOCOL_VERSION = 1;

  public static final int MAX_FRAME_BYTES = 16_777_216;

  public static final int SOFT_RESPONSE_BYTES = 8_388_608;

  public static final int MALFORMED_LINE_TOLERANCE = 5;

  public static final int MAX_JSON_NESTING_DEPTH = 64;

  public static final int MAX_METHOD_NAME_CHARS = 128;

  public static final int MAX_ERROR_DETAIL_CHARS = 2048;

  public static final int MAX_TRACED_FRAME_CHARS = 4_096;

  public static final int EXIT_OK = 0;

  public static final int EXIT_PROTOCOL_DESYNCHRONIZED = 70;

  public static final int EXIT_PLATFORM_ENCODING = 78;

  public static final int EXIT_DATA_DIRECTORY_UNUSABLE = 73;

  public static final int MAX_SOURCE_FILE_BYTES = 2_097_152;

  public static final int MAX_MODEL_SOURCE_BYTES = 16_777_216;

  public static final int MAX_SOURCE_NESTING_DEPTH = 1_000;

  public static final int MAX_ENUMERATED_FILES = 200_000;

  public static final int MAX_JAVA_SOURCES = 60_000;

  public static final int MAX_DIRECTORY_DEPTH = 64;

  public static final int MAX_PATH_BYTES = 1_024;

  public static final int MAX_ELEMENT_ID_BYTES = 4_096;

  public static final int MAX_LABEL_CHARS = 512;

  public static final int MAX_DISPLAY_NAME_CHARS = 255;

  public static final int MAX_BRANCH_NAME_CHARS = 255;

  public static final int MAX_DESCRIBED_MODULES = 256;

  public static final int MAX_BUILD_FILE_BYTES = 1_048_576;

  public static final int MAX_GIT_CONFIG_BYTES = 1_048_576;

  public static final int MAX_PACKED_REFS_BYTES = 67_108_864;

  public static final int PACKAGE_DECLARATION_SCAN_BYTES = 65_536;

  public static final int MAX_REPOSITORY_WARNINGS = 100;

  public static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5_000;

  public static final int SQLITE_READER_CONNECTIONS = 4;

  public static final int SQLITE_CACHE_SIZE_KIB = 65_536;

  public static final long SQLITE_MMAP_SIZE_BYTES = 268_435_456L;

  public static final long SQLITE_JOURNAL_SIZE_LIMIT_BYTES = 67_108_864L;

  public static final int MAX_CACHE_BACKUPS = 2;

  public static final int CACHE_LOCK_WAIT_MILLIS = 3_500;

  public static final int ELEMENT_FLUSH_ROWS = 32_768;

  public static final int ROWS_PER_INSERT = 500;

  public static final int DEPENDENCY_EVIDENCE_CAP = 20;

  public static final int CPU_POOL_MAX_THREADS = 8;

  public static final int PROGRESS_THROTTLE_MILLIS = 100;

  public static final int RUN_STOP_WAIT_MILLIS = 2_500;

  public static final int WALK_CHECK_STRIDE_ENTRIES = 1_024;

  public static final int MAX_COMMITS_WALKED = 200_000;

  public static final int MAX_SOURCE_WARNINGS_PER_CODE = 100;

  public static final int MAX_GIT_ALTERNATES = 8;

  public static final int MAX_GIT_ALTERNATES_DEPTH = 6;

  public static final int MAX_OBJECT_STORE_ENTRIES = 200_000;

  public static final long MAX_PACK_INDEX_BYTES = 268_435_456L;

  public static final long MAX_PACK_INDEX_TOTAL_BYTES = 536_870_912L;

  public static final long MAX_COMMIT_GRAPH_BYTES = 268_435_456L;

  public static final double PAGERANK_DAMPING = 0.85d;

  public static final double PAGERANK_TOLERANCE = 1e-10d;

  public static final int PAGERANK_MAX_ITER = 200;

  public static final int BETWEENNESS_MAX_NODES = 1_000;

  public static final int BETWEENNESS_MAX_EDGES = 20_000;

  public static final int GRAPH_READ_CHECK_STRIDE_ROWS = 8_192;

  public static final int GRAPH_SLICE_PACKAGE_MAX_NODES = 300;

  public static final int GRAPH_SLICE_PACKAGE_MAX_EDGES = 1_200;

  public static final int GRAPH_SLICE_TYPE_MAX_NODES = 1_000;

  public static final int GRAPH_SLICE_TYPE_MAX_EDGES = 5_000;

  public static final int OUTLINE_PAGE_DEFAULT = 200;

  public static final int OUTLINE_PAGE_MAX = 500;

  public static final int SEARCH_RESULTS_MAX = 50;

  public static final int MAX_CURSOR_CHARS = 256;

  public static final int MAX_SEARCH_QUERY_CODE_POINTS = 128;

  private AnalysisConstants() {}

  public static Map<String, Object> asMetadata() {
    final Map<String, Object> metadata = new TreeMap<>();
    for (final Field field : AnalysisConstants.class.getDeclaredFields()) {
      final int modifiers = field.getModifiers();
      if (Modifier.isPublic(modifiers)
          && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)) {
        try {
          metadata.put(field.getName(), field.get(null));
        } catch (IllegalAccessException impossible) {
          throw new IllegalStateException(
              "campo público inacessível: " + field.getName(), impossible);
        }
      }
    }
    return Collections.unmodifiableMap(metadata);
  }
}
