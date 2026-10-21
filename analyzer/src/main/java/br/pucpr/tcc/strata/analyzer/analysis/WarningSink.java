package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;

@FunctionalInterface
public interface WarningSink {

  void warn(ProtocolError warning);
}
