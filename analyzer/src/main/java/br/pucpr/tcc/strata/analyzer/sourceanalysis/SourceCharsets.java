package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SourceCharsets {

  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

  private SourceCharsets() {}

  public static Charset detect(byte[] content) {
    Objects.requireNonNull(content, "content");
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content));
      return StandardCharsets.UTF_8;
    } catch (CharacterCodingException notUtf8) {
      return WINDOWS_1252;
    }
  }
}
