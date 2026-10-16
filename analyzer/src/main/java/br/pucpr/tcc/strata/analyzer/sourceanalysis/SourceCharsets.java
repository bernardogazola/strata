package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SourceCharsets {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private SourceCharsets() {}

  public static Charset detect(byte[] content) {
    Objects.requireNonNull(content, "content");
    return detect(content, 0);
  }

  public static char[] decode(byte[] content) {
    Objects.requireNonNull(content, "content");
    final int start = startsWithUtf8Bom(content) ? UTF8_BOM.length : 0;
    return new String(content, start, content.length - start, detect(content, start)).toCharArray();
  }

  private static Charset detect(byte[] content, int start) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content, start, content.length - start));
      return StandardCharsets.UTF_8;
    } catch (CharacterCodingException notUtf8) {
      return WINDOWS_1252;
    }
  }

  private static boolean startsWithUtf8Bom(byte[] content) {
    return content.length >= UTF8_BOM.length
        && content[0] == UTF8_BOM[0]
        && content[1] == UTF8_BOM[1]
        && content[2] == UTF8_BOM[2];
  }
}
