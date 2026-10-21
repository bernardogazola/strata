package br.pucpr.tcc.strata.analyzer.protocol;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.util.concurrent.locks.ReentrantLock;

public final class JsonLines {

  private JsonLines() {}

  public static ObjectMapper strictInboundMapper() {
    final JsonFactory factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxDocumentLength(AnalysisConstants.MAX_FRAME_BYTES)
                    .maxStringLength(AnalysisConstants.MAX_FRAME_BYTES)
                    .maxNestingDepth(AnalysisConstants.MAX_JSON_NESTING_DEPTH)
                    .build())
            .build();

    final ObjectMapper mapper =
        JsonMapper.builder(factory)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    for (final LogicalType scalar :
        new LogicalType[] {LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
      mapper.coercionConfigFor(scalar).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }
    mapper
        .coercionConfigFor(LogicalType.Boolean)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    for (final CoercionInputShape scalar :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) {
      mapper.coercionConfigFor(LogicalType.Textual).setCoercion(scalar, CoercionAction.Fail);
    }
    return mapper;
  }

  private static final boolean TRACE = "1".equals(System.getenv("STRATA_PROTOCOL_TRACE"));

  private static void trace(String direction, byte[] frame) {
    if (!TRACE) {
      return;
    }
    final String text = new String(frame, java.nio.charset.StandardCharsets.UTF_8);
    Log.info(
        "trace "
            + direction
            + " "
            + (text.length() <= AnalysisConstants.MAX_TRACED_FRAME_CHARS
                ? text
                : text.substring(0, AnalysisConstants.MAX_TRACED_FRAME_CHARS) + "…truncada"));
  }

  public sealed interface ReadResult {

    record Frame(byte[] bytes) implements ReadResult {}

    record Oversized(long receivedBytes) implements ReadResult {}

    record EndOfStream() implements ReadResult {}
  }

  public static final class FrameTooLargeException extends IOException {

    @Serial private static final long serialVersionUID = 1L;

    private final int limitBytes;
    private final long receivedBytes;

    FrameTooLargeException(int limitBytes, long receivedBytes) {
      super("frame de " + receivedBytes + " bytes passa do limite de " + limitBytes + " bytes");
      this.limitBytes = limitBytes;
      this.receivedBytes = receivedBytes;
    }

    public int limitBytes() {
      return limitBytes;
    }

    public long receivedBytes() {
      return receivedBytes;
    }
  }

  public static final class Reader {

    private final InputStream in;
    private final int maxFrameBytes;
    private final byte[] chunk = new byte[1 << 16];
    private int pos;
    private int limit;

    public Reader(InputStream in, int maxFrameBytes) {
      this.in = in;
      this.maxFrameBytes = maxFrameBytes;
    }

    public ReadResult read() throws IOException {
      final MessageSizeGuard guard = new MessageSizeGuard(maxFrameBytes);
      boolean consumedSomething = false;
      while (true) {
        if (pos == limit) {
          final int read = in.read(chunk);
          pos = 0;
          if (read < 0) {
            limit = 0;
            return consumedSomething ? finish(guard) : new ReadResult.EndOfStream();
          }
          limit = read;
        }
        consumedSomething = true;
        int newline = -1;
        for (int i = pos; i < limit; i++) {
          if (chunk[i] == (byte) 0x0A) {
            newline = i;
            break;
          }
        }
        final int end = newline < 0 ? limit : newline;
        if (end > pos) {
          guard.write(chunk, pos, end - pos);
        }
        pos = newline < 0 ? limit : newline + 1;
        if (newline >= 0) {
          return finish(guard);
        }
      }
    }

    private static ReadResult finish(MessageSizeGuard guard) {
      if (!guard.withinLimit()) {
        return new ReadResult.Oversized(guard.countedBytes());
      }
      final byte[] bytes = guard.retainedBytes();
      trace("<-", bytes);
      return new ReadResult.Frame(bytes);
    }
  }

  public static final class Writer implements FrameSink {

    private final OutputStream out;
    private final CanonicalJson json;
    private final int maxFrameBytes;
    private final ReentrantLock lock = new ReentrantLock();

    public Writer(OutputStream out, CanonicalJson json, int maxFrameBytes) {
      this.out = out;
      this.json = json;
      this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public void emit(Envelope envelope) throws IOException {
      final MessageSizeGuard guard = new MessageSizeGuard(maxFrameBytes);
      json.writeTo(envelope, guard);
      if (!guard.withinLimit()) {
        throw new FrameTooLargeException(maxFrameBytes, guard.countedBytes());
      }
      final byte[] frame = guard.retainedBytes();
      trace("->", frame);
      lock.lock();
      try {
        out.write(frame);
        out.write('\n');
        out.flush();
      } finally {
        lock.unlock();
      }
    }
  }
}
