package br.pucpr.tcc.strata.analyzer.protocol;

import java.io.IOException;

public interface FrameSink {

  void emit(Envelope envelope) throws IOException;
}
