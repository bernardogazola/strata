package br.pucpr.tcc.strata.analyzer.analysis;

import static java.nio.charset.StandardCharsets.UTF_8;

import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class RecordingFrameSink implements FrameSink {

  static final Set<String> TERMINAL =
      Set.of("analysis.completed", "analysis.cancelled", "analysis.failed");

  private static final CanonicalJson CANONICAL = new CanonicalJson();
  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<Frame> frames = new ArrayList<>();
  private volatile Consumer<Frame> observer = frame -> {};

  record Frame(int index, String line, JsonNode json, Thread thread, long nanos) {

    String event() {
      return json.path("event").asText("");
    }

    JsonNode payload() {
      return json.path("payload");
    }

    String analysisId() {
      return payload().path("analysisId").asText("");
    }

    boolean isOf(AnalysisId id) {
      return id.value().equals(analysisId());
    }

    boolean isTerminal() {
      return TERMINAL.contains(event());
    }

    String stage() {
      return payload().path("stage").asText("");
    }
  }

  @Override
  public void emit(Envelope envelope) throws IOException {
    final byte[] bytes = CANONICAL.toBytes(envelope);
    final Frame frame;
    synchronized (this) {
      frame =
          new Frame(
              frames.size(),
              new String(bytes, UTF_8),
              JSON.readTree(bytes),
              Thread.currentThread(),
              System.nanoTime());
      frames.add(frame);
      notifyAll();
    }
    observer.accept(frame);
  }

  void observe(Consumer<Frame> next) {
    observer = Objects.requireNonNull(next, "next");
  }

  synchronized List<Frame> frames() {
    return List.copyOf(frames);
  }

  List<Frame> of(AnalysisId id) {
    return frames().stream().filter(frame -> frame.isOf(id)).toList();
  }

  List<Frame> events(AnalysisId id, String event) {
    return of(id).stream().filter(frame -> event.equals(frame.event())).toList();
  }

  synchronized Frame await(Predicate<Frame> wanted, Duration timeout, String what)
      throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      for (final Frame frame : frames) {
        if (wanted.test(frame)) {
          return frame;
        }
      }
      final long left = deadline - System.nanoTime();
      if (left <= 0) {
        throw new AssertionError(
            "esperei "
                + timeout.toMillis()
                + " ms por "
                + what
                + "; chegaram "
                + frames.size()
                + " quadro(s): "
                + frames.stream().map(Frame::line).toList());
      }
      wait(Math.max(1, left / 1_000_000));
    }
  }

  Frame awaitTerminal(AnalysisId id, Duration timeout) throws InterruptedException {
    return await(
        frame -> frame.isOf(id) && frame.isTerminal(), timeout, "o evento terminal de " + id);
  }
}
