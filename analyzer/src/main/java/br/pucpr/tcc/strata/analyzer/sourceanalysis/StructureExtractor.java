package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import java.util.List;
import java.util.Objects;

public final class StructureExtractor implements BatchVisitor {

  private final List<SourceRoot> roots;
  private final StructureSink sink;

  public StructureExtractor(List<SourceRoot> roots, StructureSink sink) {
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  @Override
  public void begin(List<SourceFile> files) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  @Override
  public void visit(ModeledBatch batch) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  @Override
  public void end(SourceAnalysis analysis) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public List<SourceIssue> issues() {
    throw new UnsupportedOperationException("ainda não implementado");
  }
}
