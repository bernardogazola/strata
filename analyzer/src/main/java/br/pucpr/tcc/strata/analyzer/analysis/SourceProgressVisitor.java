package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchVisitor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ModeledBatch;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import java.util.List;
import java.util.Objects;

final class SourceProgressVisitor implements BatchVisitor {

  private final BatchVisitor delegate;
  private final ProgressReporter progress;
  private final long total;
  private long completed;

  SourceProgressVisitor(BatchVisitor delegate, ProgressReporter progress, long total) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.total = total;
  }

  @Override
  public void begin(List<SourceFile> files) {
    delegate.begin(files);
    completed = files.stream().filter(file -> file.status() != SourceFileStatus.ANALYZED).count();
    report();
  }

  @Override
  public void visit(ModeledBatch batch) {
    final int files = batch.files().size();
    delegate.visit(batch);
    completed += files;
    report();
  }

  @Override
  public void end(SourceAnalysis analysis) {
    delegate.end(analysis);
    progress.finish(ProgressUnit.JAVA_FILES, total, total);
  }

  private void report() {
    progress.report(ProgressUnit.JAVA_FILES, Math.min(total, completed), total);
  }
}
