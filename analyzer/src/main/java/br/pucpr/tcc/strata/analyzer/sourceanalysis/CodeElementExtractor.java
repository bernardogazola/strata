package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import java.util.List;
import java.util.Objects;
import spoon.reflect.declaration.CtCompilationUnit;

public final class CodeElementExtractor implements BatchVisitor {

  private final List<SourceRoot> roots;
  private final ElementSink sink;

  public CodeElementExtractor(List<SourceRoot> roots, ElementSink sink) {
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

  public UnitElements extract(ModeledBatch batch, String relativePath, CtCompilationUnit unit) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public void collect(ModeledBatch batch, List<UnitElements> units) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public List<SourceIssue> issues() {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  public record UnitElements(String relativePath, BatchElements elements) {

    public UnitElements {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(elements, "elements");
    }
  }
}
