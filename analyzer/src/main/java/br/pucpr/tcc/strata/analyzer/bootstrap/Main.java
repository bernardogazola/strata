package br.pucpr.tcc.strata.analyzer.bootstrap;

import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Dispatcher;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemPing;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemShutdown;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    final StdoutCapture capture = StdoutCapture.install();
    final String pathEncoding = System.getProperty("sun.jnu.encoding");
    if (PathEncodingGuard.refuses(System.getProperty("os.name"), pathEncoding)) {
      Log.error(
          "sun.jnu.encoding vale "
              + pathEncoding
              + "; decodificar caminhos de arquivo exige um locale UTF-8");
      System.exit(AnalysisConstants.EXIT_PLATFORM_ENCODING);
      return;
    }
    int exitCode = AnalysisConstants.EXIT_OK;
    try {
      final RuntimeArguments arguments = RuntimeArguments.parse(args);
      Log.info("pasta de dados: " + arguments.dataDir());

      final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
      final JsonLines.Writer writer =
          new JsonLines.Writer(
              capture.protocolStream(), new CanonicalJson(), AnalysisConstants.MAX_FRAME_BYTES);

      writer.emit(Envelope.protocolReady(analyzerVersion()));

      final List<MethodHandler<?>> handlers =
          List.of(new SystemPing(Uptime.monotonic()), new SystemShutdown(shutdownRequested));
      final Dispatcher dispatcher = Dispatcher.of(JsonLines.strictInboundMapper(), handlers);

      exitCode =
          new Lifecycle(
                  new JsonLines.Reader(System.in, AnalysisConstants.MAX_FRAME_BYTES),
                  writer,
                  dispatcher,
                  shutdownRequested,
                  AnalysisConstants.MALFORMED_LINE_TOLERANCE)
              .run();
    } catch (IllegalArgumentException badArguments) {
      Log.error("linha de comando inválida: " + badArguments.getMessage());
      exitCode = AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED;
    } catch (IOException | RuntimeException fatal) {
      Log.error("o analisador terminou de forma anormal", fatal);
      exitCode = AnalysisConstants.EXIT_PROTOCOL_DESYNCHRONIZED;
    } finally {
      try {
        capture.flush();
      } catch (IOException ignored) {
        Log.error("não foi possível descarregar o fluxo do protocolo na saída");
      }
    }
    System.exit(exitCode);
  }

  private static String analyzerVersion() throws IOException {
    try (InputStream in = Main.class.getResourceAsStream("/analyzer.properties")) {
      if (in == null) {
        throw new IOException("analyzer.properties não está no classpath");
      }
      final Properties properties = new Properties();
      properties.load(in);
      final String version = properties.getProperty("version");
      if (version == null || version.isBlank()) {
        throw new IOException("analyzer.properties não tem a chave version");
      }
      return version;
    }
  }
}
