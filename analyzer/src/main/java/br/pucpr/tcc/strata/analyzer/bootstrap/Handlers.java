package br.pucpr.tcc.strata.analyzer.bootstrap;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisRegistry;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.method.AnalysisCancel;
import br.pucpr.tcc.strata.analyzer.protocol.method.AnalysisStart;
import br.pucpr.tcc.strata.analyzer.protocol.method.AnalysisStatusQuery;
import br.pucpr.tcc.strata.analyzer.protocol.method.RepositoryDescribe;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemPing;
import br.pucpr.tcc.strata.analyzer.protocol.method.SystemShutdown;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Handlers {

  private Handlers() {}

  public static List<MethodHandler<?>> all(
      Uptime uptime, AtomicBoolean shutdownRequested, AnalysisRegistry analyses) {
    Objects.requireNonNull(analyses, "analyses");
    HermeticSystemReader.install();
    return List.of(
        new SystemPing(uptime),
        new SystemShutdown(shutdownRequested),
        new RepositoryDescribe(new RepositoryValidator()),
        new AnalysisStart(analyses),
        new AnalysisCancel(analyses),
        new AnalysisStatusQuery(analyses));
  }
}
