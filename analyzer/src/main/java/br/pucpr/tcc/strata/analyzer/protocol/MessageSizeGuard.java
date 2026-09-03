package br.pucpr.tcc.strata.analyzer.protocol;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

public final class MessageSizeGuard extends OutputStream {

  private final int maxBytes;
  private ByteArrayOutputStream retained;
  private long counted;

  public MessageSizeGuard(int maxBytes) {
    this.maxBytes = maxBytes;
    this.retained = new ByteArrayOutputStream(Math.min(maxBytes, 256));
  }

  @Override
  public void write(int b) {
    counted++;
    if (retained != null) {
      if (counted > maxBytes) {
        retained = null;
      } else {
        retained.write(b);
      }
    }
  }

  @Override
  public void write(byte[] bytes, int offset, int length) {
    counted += length;
    if (retained != null) {
      if (counted > maxBytes) {
        retained = null;
      } else {
        retained.write(bytes, offset, length);
      }
    }
  }

  public boolean withinLimit() {
    return counted <= maxBytes;
  }

  public long countedBytes() {
    return counted;
  }

  public byte[] retainedBytes() {
    if (retained == null) {
      throw new IllegalStateException("o conteúdo passou do teto e não foi retido");
    }
    return retained.toByteArray();
  }
}
