package br.pucpr.tcc.strata.analyzer.bootstrap;

import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.FrameSink;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Lifecycle {

  private final JsonLines.Reader reader;
  private final FrameSink sink;
  private final Dispatcher dispatcher;
  private final AtomicBoolean shutdownRequested;
  private final int malformedTolerance;

  private int malformed;

  public Lifecycle(
      JsonLines.Reader reader,
      FrameSink sink,
      Dispatcher dispatcher,
      AtomicBoolean shutdownRequested,
      int malformedTolerance) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.shutdownRequested = Objects.requireNonNull(shutdownRequested, "shutdownRequested");
    this.malformedTolerance = malformedTolerance;
  }

  public int run() throws IOException {
    while (true) {
      final JsonLines.ReadResult result = reader.read();
      switch (result) {
        case JsonLines.ReadResult.EndOfStream ignored -> {
          return AnalysisConstants.EXIT_OK;
        }
        case JsonLines.ReadResult.Oversized oversized -> {
          if (countMalformed("linha de entrada de " + oversized.receivedBytes() + " bytes")) {
            return AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED;
          }
        }
        case JsonLines.ReadResult.Frame frame -> {
          final JsonNode node;
          try {
            node = dispatcher.parse(frame.bytes());
          } catch (IOException malformedJson) {
            if (countMalformed("linha ilegível: " + malformedJson.getMessage())) {
              return AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED;
            }
            continue;
          }
          switch (dispatcher.dispatch(node)) {
            case Dispatcher.Outcome.Discard discard -> {
              if (countMalformed("frame descartado: " + discard.reason())) {
                return AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED;
              }
            }
            case Dispatcher.Outcome.Respond respond -> {
              if (emit(respond.envelope())) {
                runAfterEmit(respond.afterEmit());
              }
              if (shutdownRequested.get()) {
                return AnalysisConstants.EXIT_OK;
              }
            }
          }
        }
      }
    }
  }

  public int malformedCount() {
    return malformed;
  }

  private boolean emit(Envelope envelope) throws IOException {
    try {
      sink.emit(envelope);
      return true;
    } catch (JsonLines.FrameTooLargeException tooLarge) {
      if (envelope instanceof Envelope.OkResponse response) {
        sink.emit(
            Envelope.error(
                response.id(),
                ErrorCodes.messageTooLarge(tooLarge.limitBytes(), tooLarge.receivedBytes())));
      } else {
        Log.error("descartando um frame grande demais que não traz id para responder", tooLarge);
      }
      return false;
    }
  }

  private static void runAfterEmit(Runnable afterEmit) {
    try {
      afterEmit.run();
    } catch (RuntimeException failed) {
      Log.error("o gancho de uma resposta emitida falhou", failed);
    }
  }

  private boolean countMalformed(String reason) {
    malformed++;
    Log.warn("frame malformado " + malformed + "/" + malformedTolerance + ": " + reason);
    if (malformed > malformedTolerance) {
      Log.error("fluxo do protocolo dessincronizado; encerrando");
      return true;
    }
    return false;
  }
}
