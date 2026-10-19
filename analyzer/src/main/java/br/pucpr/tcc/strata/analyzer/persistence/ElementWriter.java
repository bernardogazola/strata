package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ElementSink;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public final class ElementWriter implements ElementSink {

  private final SqliteConnections connections;
  private final long analysisPk;
  private final List<SourceRoot> roots;
  private final List<JavaSource> sources;

  public ElementWriter(
      SqliteConnections connections,
      long analysisPk,
      List<SourceRoot> roots,
      List<JavaSource> sources) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.analysisPk = analysisPk;
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
  }

  @Override
  public void begin(List<SourceFile> files) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  @Override
  public void accept(BatchElements elements) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  @Override
  public void end(List<SourceFile> files, List<FileElement> fileElements) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public OptionalLong pkOf(CodeElementId id) {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
