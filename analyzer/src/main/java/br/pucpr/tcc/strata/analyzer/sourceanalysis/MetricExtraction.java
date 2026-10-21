package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import java.util.List;
import java.util.Objects;

final class MetricExtraction {

  UnitMetrics extract(ModeledBatch batch, String relativePath, UnitExtraction.Walked walked) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  record UnitMetrics(String relativePath, List<CodeMetric> metrics) {

    UnitMetrics {
      Objects.requireNonNull(relativePath, "relativePath");
      metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
    }
  }
}
