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

  public static final int MAX_SOURCE_FILE_BYTES = 2_097_152;

  public static final int MAX_MODEL_SOURCE_BYTES = 16_777_216;

  public static final int MAX_SOURCE_NESTING_DEPTH = 1_000;

  public static final int MAX_ENUMERATED_FILES = 200_000;

  public static final int MAX_JAVA_SOURCES = 60_000;

  public static final int MAX_DIRECTORY_DEPTH = 64;

  public static final int MAX_PATH_BYTES = 1_024;

  public static final int MAX_DISPLAY_NAME_CHARS = 255;

  public static final int MAX_BRANCH_NAME_CHARS = 255;

  public static final int MAX_BUILD_FILE_BYTES = 1_048_576;

  public static final int MAX_GIT_CONFIG_BYTES = 1_048_576;

  public static final int MAX_PACKED_REFS_BYTES = 67_108_864;

  public static final int PACKAGE_DECLARATION_SCAN_BYTES = 65_536;

  public static final int MAX_REPOSITORY_WARNINGS = 100;

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
