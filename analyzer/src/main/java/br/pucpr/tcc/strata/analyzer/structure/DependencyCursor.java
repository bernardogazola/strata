package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Objects;

public record DependencyCursor(DependencyDirection direction, long anchorPk, long lastPk) {

  public static final byte VERSION = 2;

  private static final byte IN_BYTE = 0;

  private static final byte OUT_BYTE = 1;

  private static final int DECODED_BYTES = 2 + 2 * Long.BYTES;

  private static final int ENCODED_CHARS = 24;

  public DependencyCursor {
    Objects.requireNonNull(direction, "direction");
    if (anchorPk < 1 || lastPk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + anchorPk + "/" + lastPk);
    }
  }

  public String encode() {
    final byte directionByte = direction == DependencyDirection.IN ? IN_BYTE : OUT_BYTE;
    final byte[] bytes =
        ByteBuffer.allocate(DECODED_BYTES)
            .put(VERSION)
            .put(directionByte)
            .putLong(anchorPk)
            .putLong(lastPk)
            .array();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static DependencyCursor decode(String wire) {
    if (wire == null
        || wire.length() > AnalysisConstants.MAX_CURSOR_CHARS
        || wire.length() != ENCODED_CHARS
        || !OutlineCursor.isUrlAlphabet(wire)) {
      throw malformed();
    }
    final ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(wire));
    if (bytes.remaining() != DECODED_BYTES || bytes.get() != VERSION) {
      throw malformed();
    }
    final DependencyDirection direction =
        switch (bytes.get()) {
          case IN_BYTE -> DependencyDirection.IN;
          case OUT_BYTE -> DependencyDirection.OUT;
          default -> throw malformed();
        };
    final long anchorPk = bytes.getLong();
    final long lastPk = bytes.getLong();
    if (anchorPk < 1 || lastPk < 1) {
      throw malformed();
    }
    return new DependencyCursor(direction, anchorPk, lastPk);
  }

  private static InvalidStructureRequest malformed() {
    return new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_MALFORMED);
  }
}
