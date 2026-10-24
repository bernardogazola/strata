package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.nio.ByteBuffer;
import java.util.Base64;

public record OutlineCursor(long elementPk) {

  public static final byte VERSION = 1;

  private static final int DECODED_BYTES = 1 + Long.BYTES;

  private static final int ENCODED_CHARS = 12;

  public OutlineCursor {
    if (elementPk < 1) {
      throw new IllegalArgumentException("um pk de elemento é >= 1: " + elementPk);
    }
  }

  public String encode() {
    final byte[] bytes = ByteBuffer.allocate(DECODED_BYTES).put(VERSION).putLong(elementPk).array();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static OutlineCursor decode(String wire) {
    if (wire == null
        || wire.length() > AnalysisConstants.MAX_CURSOR_CHARS
        || wire.length() != ENCODED_CHARS
        || !isUrlAlphabet(wire)) {
      throw malformed();
    }
    final ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(wire));
    if (bytes.remaining() != DECODED_BYTES || bytes.get() != VERSION) {
      throw malformed();
    }
    final long elementPk = bytes.getLong();
    if (elementPk < 1) {
      throw malformed();
    }
    return new OutlineCursor(elementPk);
  }

  private static boolean isUrlAlphabet(String wire) {
    for (int index = 0; index < wire.length(); index++) {
      final char c = wire.charAt(index);
      final boolean allowed =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  private static InvalidStructureRequest malformed() {
    return new InvalidStructureRequest(InvalidStructureRequest.Reason.CURSOR_MALFORMED);
  }
}
