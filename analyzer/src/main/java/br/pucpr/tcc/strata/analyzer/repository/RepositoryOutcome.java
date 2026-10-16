package br.pucpr.tcc.strata.analyzer.repository;

import java.util.List;
import java.util.Objects;

public sealed interface RepositoryOutcome<T> {

  record Accepted<T>(T value, List<RepositoryIssue> warnings) implements RepositoryOutcome<T> {

    public Accepted {
      Objects.requireNonNull(value, "value");
      warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
  }

  record Rejected<T>(RepositoryIssue reason) implements RepositoryOutcome<T> {

    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
