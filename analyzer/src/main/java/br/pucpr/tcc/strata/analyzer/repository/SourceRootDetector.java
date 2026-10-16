package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.file.Path;
import java.util.List;

final class SourceRootDetector {

  List<SourceRoot> detect(Path root, RepositoryInventory inventory, BuildDescription build) {
    throw new UnsupportedOperationException(
        "ainda não implementado: SourceRootDetector " + root + inventory + build);
  }
}
