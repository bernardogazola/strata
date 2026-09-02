package br.pucpr.tcc.strata.analyzer.bootstrap;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class StdoutCapture {

  private static final AtomicReference<StdoutCapture> INSTALLED = new AtomicReference<>();

  private final OutputStream protocolStream;

  private StdoutCapture(OutputStream protocolStream) {
    this.protocolStream = protocolStream;
  }

  public static StdoutCapture install() {
    final PrintStream original = System.out;
    original.flush();

    final OutputStream frames =
        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

    final StdoutCapture capture = new StdoutCapture(frames);
    if (!INSTALLED.compareAndSet(null, capture)) {
      throw new IllegalStateException("StdoutCapture.install() foi chamado duas vezes");
    }
    return capture;
  }

  public OutputStream protocolStream() {
    return protocolStream;
  }

  public void flush() throws IOException {
    protocolStream.flush();
  }
}
