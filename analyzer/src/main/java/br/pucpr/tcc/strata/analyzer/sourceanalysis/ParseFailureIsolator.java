package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

final class ParseFailureIsolator<F, M> {

  private static final int[] NONE = {};

  private final Builder<F, M> builder;

  ParseFailureIsolator(Builder<F, M> builder) {
    this.builder = Objects.requireNonNull(builder, "builder");
  }

  Isolation<F> isolate(List<F> files, BatchConsumer<F, M> consumer) {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(consumer, "consumer");
    final Run run = new Run(List.copyOf(files), consumer);
    run.analyze(IntStream.range(0, files.size()).toArray());
    return run.result();
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

  record Isolation<F>(List<List<F>> batches, List<F> failed, int builds, int quarantines) {

    public Isolation {
      batches = batches.stream().map(List::copyOf).toList();
      failed = List.copyOf(failed);
    }
  }

  private final class Run {

    private final List<F> files;
    private final BatchConsumer<F, M> consumer;
    private final List<List<F>> batches = new ArrayList<>();
    private final BitSet failed = new BitSet();
    private int builds;
    private int quarantines;

    private Run(List<F> files, BatchConsumer<F, M> consumer) {
      this.files = files;
      this.consumer = consumer;
    }

    void analyze(int[] indexes) {
      int[] remaining = indexes;
      while (remaining.length > 0) {
        if (buildAndDeliver(remaining)) {
          return;
        }
        if (remaining.length == 1) {
          failed.set(remaining[0]);
          return;
        }
        final int[] left = firstHalf(remaining);
        final int[] right = secondHalf(remaining);
        final boolean leftBuilds = probe(left);
        final boolean rightBuilds = probe(right);
        final int[] culprits =
            union(leftBuilds ? NONE : culprits(left), rightBuilds ? NONE : culprits(right));
        if (culprits.length == 0 && (leftBuilds || rightBuilds)) {
          final int[] quarantined = leftBuilds ? quarantine(left, right) : quarantine(right, left);
          quarantines++;
          analyze(without(remaining, quarantined));
          analyze(quarantined);
          return;
        }
        if (culprits.length == 0) {
          analyze(left);
          analyze(right);
          return;
        }
        Arrays.stream(culprits).forEach(failed::set);
        remaining = without(remaining, culprits);
      }
    }

    private int[] culprits(int[] indexes) {
      if (indexes.length == 1) {
        return indexes;
      }
      final int[] first = firstHalf(indexes);
      final int[] second = secondHalf(indexes);
      final boolean firstBuilds = probe(first);
      final boolean secondBuilds = probe(second);
      return union(firstBuilds ? NONE : culprits(first), secondBuilds ? NONE : culprits(second));
    }

    private int[] quarantine(int[] base, int[] suspects) {
      int[] cleared = base;
      int[] remaining = suspects;
      while (remaining.length > 1) {
        final int[] first = firstHalf(remaining);
        final int[] second = secondHalf(remaining);
        final int[] withSecond = union(cleared, second);
        if (probe(withSecond)) {
          cleared = withSecond;
          remaining = first;
          continue;
        }
        final int[] withFirst = union(cleared, first);
        if (!probe(withFirst)) {
          return remaining;
        }
        cleared = withFirst;
        remaining = second;
      }
      return remaining;
    }

    private boolean buildAndDeliver(int[] indexes) {
      final List<F> batch = filesAt(indexes);
      if (!(build(batch) instanceof Attempt.Built<M>(final M model))) {
        return false;
      }
      batches.add(batch);
      consumer.accept(batch, model);
      return true;
    }

    private boolean probe(int[] indexes) {
      return build(filesAt(indexes)) instanceof Attempt.Built<M>;
    }

    private Attempt<M> build(List<F> batch) {
      builds++;
      return Objects.requireNonNull(builder.build(batch), "attempt");
    }

    private List<F> filesAt(int[] indexes) {
      return Arrays.stream(indexes).mapToObj(files::get).toList();
    }

    Isolation<F> result() {
      return new Isolation<>(
          batches, failed.stream().mapToObj(files::get).toList(), builds, quarantines);
    }
  }

  private static int[] firstHalf(int[] indexes) {
    return Arrays.copyOfRange(indexes, 0, indexes.length / 2);
  }

  private static int[] secondHalf(int[] indexes) {
    return Arrays.copyOfRange(indexes, indexes.length / 2, indexes.length);
  }

  private static int[] union(int[] first, int[] second) {
    return IntStream.concat(Arrays.stream(first), Arrays.stream(second)).sorted().toArray();
  }

  private static int[] without(int[] indexes, int[] removed) {
    return Arrays.stream(indexes)
        .filter(index -> Arrays.binarySearch(removed, index) < 0)
        .toArray();
  }
}
