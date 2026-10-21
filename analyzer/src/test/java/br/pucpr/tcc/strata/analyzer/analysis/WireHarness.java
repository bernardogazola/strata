package br.pucpr.tcc.strata.analyzer.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Handlers;
import br.pucpr.tcc.strata.analyzer.bootstrap.Lifecycle;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

final class WireHarness implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final PipedOutputStream stdin = new PipedOutputStream();
  private final LineCapture stdout = new LineCapture();
  private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
  private final JsonLines.Writer writer =
      new JsonLines.Writer(stdout, new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES);
  private final AnalysisRegistry registry;
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();

  WireHarness(Function<JsonLines.Writer, RegistrySettings> settings) throws IOException {
    this.registry = new AnalysisRegistry(settings.apply(writer));
    final Dispatcher dispatcher =
        Dispatcher.of(
            JsonLines.strictInboundMapper(), Handlers.all(() -> 0L, shutdownRequested, registry));
    final Lifecycle lifecycle =
        new Lifecycle(
            new JsonLines.Reader(
                new PipedInputStream(stdin, 1 << 16), AnalysisConstants.MAX_FRAME_BYTES),
            writer,
            dispatcher,
            shutdownRequested,
            AnalysisConstants.MALFORMED_LINE_TOLERANCE);
    final Thread reader =
        new Thread(
            () -> {
              try {
                exit.complete(lifecycle.run());
              } catch (IOException | RuntimeException failed) {
                exit.completeExceptionally(failed);
              }
            },
            "wire-harness-reader");
    reader.setDaemon(true);
    reader.start();
  }

  AnalysisRegistry registry() {
    return registry;
  }

  void send(String line) throws IOException {
    stdin.write((line + "\n").getBytes(UTF_8));
    stdin.flush();
  }

  void closeInput() throws IOException {
    stdin.close();
  }

  int awaitExit(Duration timeout) throws Exception {
    return exit.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  List<String> lines() {
    return stdout.lines();
  }

  int awaitLine(Predicate<JsonNode> wanted, Duration timeout, String what)
      throws InterruptedException {
    return stdout.await(wanted, timeout, what);
  }

  static JsonNode parse(String line) {
    try {
      return JSON.readTree(line);
    } catch (IOException malformed) {
      throw new UncheckedIOException(malformed);
    }
  }

  @Override
  public void close() throws IOException {
    stdin.close();
  }

  private static final class LineCapture extends OutputStream {
    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private final List<String> lines = new ArrayList<>();

    @Override
    public synchronized void write(int b) {
      if (b == '\n') {
        lines.add(current.toString(UTF_8));
        current.reset();
        notifyAll();
      } else {
        current.write(b);
      }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      for (int i = offset; i < offset + length; i++) {
        write(bytes[i]);
      }
    }

    synchronized List<String> lines() {
      return List.copyOf(lines);
    }

    synchronized int await(Predicate<JsonNode> wanted, Duration timeout, String what)
        throws InterruptedException {
      final long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        for (int i = 0; i < lines.size(); i++) {
          if (wanted.test(parse(lines.get(i)))) {
            return i;
          }
        }
        final long left = deadline - System.nanoTime();
        if (left <= 0) {
          throw new AssertionError(
              "esperei " + timeout.toMillis() + " ms por " + what + "; o fio tem " + lines);
        }
        wait(Math.max(1, left / 1_000_000));
      }
    }
  }
}
