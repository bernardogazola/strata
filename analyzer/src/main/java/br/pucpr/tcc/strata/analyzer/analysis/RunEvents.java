package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.protocol.AnalysisEvents;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class RunEvents implements WarningSink {

  private final AnalysisId id;
  private final FrameSink sink;
  private final CancellationToken token;
  private final AtomicBoolean broken = new AtomicBoolean();
  private final AtomicBoolean terminal = new AtomicBoolean();
  private final Map<String, AtomicInteger> warningsByCode = new ConcurrentHashMap<>();

  RunEvents(AnalysisId id, FrameSink sink, CancellationToken token) {
    this.id = Objects.requireNonNull(id, "id");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.token = Objects.requireNonNull(token, "token");
  }

  void started() {
    emit(AnalysisEvents.started(id));
  }

  void progress(ProgressTick tick) {
    emit(AnalysisEvents.progress(id, tick));
  }

  void stageCompleted(
      AnalysisStage stage, StageStatus status, long durationMs, StageCounters counters) {
    emit(AnalysisEvents.stageCompleted(id, stage, status, durationMs, counters));
  }

  @Override
  public void warn(ProtocolError warning) {
    final int seen =
        warningsByCode
            .computeIfAbsent(warning.code(), code -> new AtomicInteger())
            .incrementAndGet();
    if (seen <= AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE) {
      emit(AnalysisEvents.warning(id, warning));
    }
  }

  void cacheInvalidated(CacheInvalidation invalidation) {
    emit(Envelope.cacheInvalidated(invalidation));
  }

  void completed(Completeness completeness) {
    terminal(AnalysisEvents.completed(id, completeness));
  }

  void cancelled(Completeness completeness) {
    terminal(AnalysisEvents.cancelled(id, completeness));
  }

  void failed(ProtocolError error) {
    terminal(AnalysisEvents.failed(id, error));
  }

  boolean terminalEmitted() {
    return terminal.get();
  }

  private void terminal(Envelope envelope) {
    if (terminal.compareAndSet(false, true)) {
      emit(envelope);
    }
  }

  void logCappedWarnings() {
    final Map<String, Integer> capped = new TreeMap<>();
    warningsByCode.forEach(
        (code, seen) -> {
          final int over = seen.get() - AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE;
          if (over > 0) {
            capped.put(code, over);
          }
        });
    capped.forEach(
        (code, over) ->
            Log.warn(
                "análise "
                    + id.value()
                    + ": "
                    + over
                    + " aviso(s) "
                    + code
                    + " acima do limite por código de "
                    + AnalysisConstants.MAX_SOURCE_WARNINGS_PER_CODE
                    + " não foram emitidos"));
  }

  private void emit(Envelope envelope) {
    if (broken.get()) {
      return;
    }
    try {
      sink.emit(envelope);
    } catch (JsonLines.FrameTooLargeException oversized) {
      Log.error("a análise " + id.value() + " descartou um frame grande demais", oversized);
    } catch (IOException failure) {
      if (broken.compareAndSet(false, true)) {
        Log.error(
            "a análise " + id.value() + " não consegue escrever no stdout; ela se cancela",
            failure);
        token.cancel();
      }
    }
  }
}
