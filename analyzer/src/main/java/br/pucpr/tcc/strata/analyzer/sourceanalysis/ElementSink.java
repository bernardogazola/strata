package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import java.util.List;

public interface ElementSink {

  void begin(List<SourceFile> files);

  void accept(BatchElements elements);

  void end(List<SourceFile> files, List<FileElement> fileElements);
}
