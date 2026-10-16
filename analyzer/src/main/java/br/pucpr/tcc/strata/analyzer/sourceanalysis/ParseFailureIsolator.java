package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.List;
import java.util.Objects;

final class ParseFailureIsolator<F, M> {

  private final Builder<F, M> builder;

  ParseFailureIsolator(Builder<F, M> builder) {
    this.builder = Objects.requireNonNull(builder, "builder");
  }

  Isolation<F> isolate(List<F> files, BatchConsumer<F, M> consumer) {
    throw new UnsupportedOperationException("ParseFailureIsolator.isolate: ainda não implementado");
  }

  @FunctionalInterface
  interface Builder<F, M> {

    Attempt<M> build(List<F> files);
  }

  @FunctionalInterface
  interface BatchConsumer<F, M> {

    void accept(List<F> files, M model);
  }

  sealed interface Attempt<M> permits Attempt.Built, Attempt.Failed {

    record Built<M>(M model) implements Attempt<M> {

      public Built {
        Objects.requireNonNull(model, "model");
      }
    }

    record Failed<M>(Throwable cause) implements Attempt<M> {

      public Failed {
        Objects.requireNonNull(cause, "cause");
      }
    }
  }

  record Isolation<F>(List<List<F>> batches, List<F> failed, int builds) {

    public Isolation {
      batches = batches.stream().map(List::copyOf).toList();
      failed = List.copyOf(failed);
    }
  }
}
