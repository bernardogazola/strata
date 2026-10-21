package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchVisitor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ModeledBatch;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class GapCensus implements BooleanSupplier, AutoCloseable {

  static final String UNSAMPLED = "UNSAMPLED";
  static final String COMPILE = "COMPILE";

  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final long FIRST_SAMPLE_NANOS = 100 * NANOS_PER_MILLI;
  private static final long SAMPLE_EVERY_NANOS = 250 * NANOS_PER_MILLI;
  private static final long NAMED_NANOS = 100 * NANOS_PER_MILLI;
  private static final long OVER_250_NANOS = 250 * NANOS_PER_MILLI;
  private static final long OVER_1000_NANOS = 1_000 * NANOS_PER_MILLI;
  private static final long OVER_2000_NANOS = 2_000 * NANOS_PER_MILLI;
  private static final long POLL_MILLIS = 20;
  private static final int MAX_NAMED = 100_000;
  private static final int LONGEST = 10;
  private static final String TAIL = "analyze returned (tail)";

  private static final Set<String> BUILDING =
      Set.of(
          "MODEL",
          COMPILE,
          "IMPORT",
          "PRE_COMPILE_PARSE",
          "PRE_COMPILE_SETUP",
          "BUILD_OTHER",
          "LAUNCHER_SETUP",
          "BATCH_READ");

  private static final Set<String> VISITING =
      Set.of("FLUSH", "UNIT_EXTRACTION", "BATCH_POST_EXTRACTION");

  private final BooleanSupplier cancelled;
  private final List<Gap> named = new ArrayList<>();
  private final List<Visit> visits = new ArrayList<>();
  private final Map<Long, List<String>> samples = new ConcurrentHashMap<>();
  private volatile Thread target;
  private volatile long last;
  private volatile long sequence;
  private volatile boolean open;
  private volatile boolean running = true;
  private Thread sentinel;
  private long beganAt;
  private long checks;
  private long over250;
  private long over1000;
  private long over2000;
  private long longestNanos;
  private String longestClosedBy;
  private long tailNanos = -1;

  GapCensus(BooleanSupplier cancelled) {
    this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
  }

  void begin() {
    target = Thread.currentThread();
    beganAt = System.nanoTime();
    last = beganAt;
    open = true;
    sentinel = new Thread(this::watch, "stages-baseline-gap-sentinel");
    sentinel.setDaemon(true);
    sentinel.start();
  }

  @Override
  public boolean getAsBoolean() {
    final long now = System.nanoTime();
    closeGap(now, null);
    return cancelled.getAsBoolean();
  }

  void end() {
    final long now = System.nanoTime();
    tailNanos = now - last;
    closeGap(now, TAIL);
    open = false;
    close();
  }

  @Override
  public void close() {
    running = false;
    final Thread watching = sentinel;
    if (watching == null) {
      return;
    }
    try {
      watching.join(POLL_MILLIS * 50);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  BatchVisitor visiting(BatchVisitor delegate) {
    return new TimelineVisitor(delegate);
  }

  Result result() {
    final List<Classified> classified = new ArrayList<>(named.size());
    for (final Gap gap : named) {
      classified.add(classify(gap));
    }
    classified.sort(Comparator.comparingLong(Classified::nanos).reversed());
    final List<Classified> longest =
        List.copyOf(classified.subList(0, Math.min(LONGEST, classified.size())));
    return new Result(
        checks,
        millis(longestNanos),
        longestClosedBy,
        over250,
        over1000,
        over2000,
        millis(tailNanos),
        longest.isEmpty() ? null : longest.get(0),
        longest,
        classified.stream().filter(gap -> COMPILE.equals(gap.gapClass())).findFirst().orElse(null));
  }

  private void closeGap(long now, String site) {
    final long gap = now - last;
    final long closing = sequence;
    if (site == null) {
      checks++;
    }
    if (gap > longestNanos) {
      longestNanos = gap;
      longestClosedBy = site == null ? closingSite() : site;
    }
    if (gap > OVER_250_NANOS) {
      over250++;
    }
    if (gap > OVER_1000_NANOS) {
      over1000++;
    }
    if (gap > OVER_2000_NANOS) {
      over2000++;
    }
    if (gap >= NAMED_NANOS && named.size() < MAX_NAMED) {
      named.add(new Gap(closing, last, gap, gap == longestNanos ? longestClosedBy : siteOf(site)));
    }
    last = now;
    sequence = closing + 1;
  }

  private static String siteOf(String site) {
    return site == null ? closingSite() : site;
  }

  private static String closingSite() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                String.join(
                    " <- ",
                    frames
                        .filter(
                            frame -> !frame.getClassName().startsWith(GapCensus.class.getName()))
                        .limit(3)
                        .map(frame -> simple(frame.getClassName()) + "." + frame.getMethodName())
                        .toList()));
  }

  private void watch() {
    long sampled = -1;
    int taken = 0;
    while (running) {
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException interrupted) {
        return;
      }
      if (!open) {
        continue;
      }
      final long current = sequence;
      final long since = System.nanoTime() - last;
      if (current != sampled) {
        sampled = current;
        taken = 0;
      }
      if (since < (taken == 0 ? FIRST_SAMPLE_NANOS : taken * SAMPLE_EVERY_NANOS)) {
        continue;
      }
      final StackTraceElement[] stack = target.getStackTrace();
      if (sequence != current) {
        continue;
      }
      samples
          .computeIfAbsent(current, ignored -> Collections.synchronizedList(new ArrayList<>()))
          .add(label(stack));
      taken++;
    }
  }

  static String label(StackTraceElement[] stack) {
    if (has(stack, "ElementWriter", "end") || has(stack, "CodeElementExtractor", "end")) {
      return "END";
    }
    if (has(stack, "ElementWriter", "begin")) {
      return "BEGIN";
    }
    if (has(stack, "ElementWriter", "accept")) {
      return "FLUSH";
    }
    if (has(stack, "ModeledBatch", "perCompilationUnit")) {
      return "UNIT_EXTRACTION";
    }
    if (has(stack, "StructureExtractor", "visit")) {
      return "BATCH_POST_EXTRACTION";
    }
    if (has(stack, "TreeBuilderCompiler", "ignoreSyntaxErrors")
        || has(stack, "Compiler", "beginToCompile")) {
      return "PRE_COMPILE_PARSE";
    }
    if (has(stack, "TreeBuilderCompiler", "buildUnits")) {
      return COMPILE;
    }
    if (has(stack, "JDTBatchCompiler", "getUnits")) {
      return "PRE_COMPILE_SETUP";
    }
    if (has(stack, "JDTBasedSpoonCompiler", "buildModel")) {
      if (hasClassContaining(stack, "Import")) {
        return "IMPORT";
      }
      if (hasClassContaining(stack, "JDTTreeBuilder")
          || hasClassContaining(stack, "ContextBuilder")) {
        return "MODEL";
      }
      return "BUILD_OTHER";
    }
    if (has(stack, "Launcher", "buildModel")
        || hasClassContaining(stack, "JDTBasedSpoonCompiler")) {
      return "BUILD_OTHER";
    }
    if (has(stack, "SpoonLaunchers", null)) {
      return "LAUNCHER_SETUP";
    }
    if (has(stack, "SourceAnalysisRun", "buildIndex")) {
      return "NAME_INDEX";
    }
    if (has(stack, "SourceAnalysisRun", "preParseRoot")) {
      return "PRE_PARSE";
    }
    if (has(stack, "SourceAnalysisRun", "readForBatch")) {
      return "BATCH_READ";
    }
    if (has(stack, "SourceAnalysisRun", "result")) {
      return "RESULT";
    }
    return "OTHER " + topOwn(stack);
  }

  private static boolean has(StackTraceElement[] stack, String type, String method) {
    for (final StackTraceElement frame : stack) {
      if (simple(frame.getClassName()).equals(type)
          && (method == null || frame.getMethodName().equals(method))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasClassContaining(StackTraceElement[] stack, String part) {
    for (final StackTraceElement frame : stack) {
      if (simple(frame.getClassName()).contains(part)) {
        return true;
      }
    }
    return false;
  }

  private static String topOwn(StackTraceElement[] stack) {
    for (final StackTraceElement frame : stack) {
      if (frame.getClassName().startsWith("br.pucpr.tcc.strata")) {
        return simple(frame.getClassName()) + "." + frame.getMethodName();
      }
    }
    return stack.length == 0
        ? "?"
        : simple(stack[0].getClassName()) + "." + stack[0].getMethodName();
  }

  private static String simple(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private Classified classify(Gap gap) {
    final List<String> taken = samples.getOrDefault(gap.sequence(), List.of());
    final Map<String, Integer> counts = new LinkedHashMap<>();
    synchronized (taken) {
      for (final String sample : taken) {
        counts.merge(sample, 1, Integer::sum);
      }
    }
    String gapClass = UNSAMPLED;
    int most = 0;
    for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > most) {
        gapClass = entry.getKey();
        most = entry.getValue();
      }
    }
    return new Classified(
        gap.nanos(),
        millis(gap.nanos()),
        gapClass,
        Map.copyOf(counts),
        gap.closedBy(),
        batchOf(gapClass, gap),
        millis(gap.startNanos() - beganAt));
  }

  private String batchOf(String gapClass, Gap gap) {
    final long end = gap.startNanos() + gap.nanos();
    if (BUILDING.contains(gapClass)) {
      return visits.stream()
          .filter(visit -> visit.startNanos() >= end)
          .findFirst()
          .map(visit -> "next batch built: " + visit.describe())
          .orElse(null);
    }
    if (VISITING.contains(gapClass)) {
      return visits.stream()
          .filter(
              visit ->
                  visit.startNanos() <= gap.startNanos() && gap.startNanos() <= visit.endNanos())
          .findFirst()
          .map(visit -> "batch visited: " + visit.describe())
          .orElse(null);
    }
    return null;
  }

  private static long millis(long nanos) {
    return nanos < 0 ? -1 : nanos / NANOS_PER_MILLI;
  }

  private final class TimelineVisitor implements BatchVisitor {

    private final BatchVisitor delegate;

    private TimelineVisitor(BatchVisitor delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void begin(List<SourceFile> files) {
      delegate.begin(files);
    }

    @Override
    public void visit(ModeledBatch batch) {
      final long started = System.nanoTime();
      final String root = batch.root().relativePath();
      final int files = batch.files().size();
      try {
        delegate.visit(batch);
      } finally {
        visits.add(new Visit(started, System.nanoTime(), root, files));
      }
    }

    @Override
    public void end(SourceAnalysis analysis) {
      delegate.end(analysis);
    }
  }

  private record Gap(long sequence, long startNanos, long nanos, String closedBy) {}

  private record Visit(long startNanos, long endNanos, String root, int files) {

    String describe() {
      return "'" + root + "' (" + files + " file(s))";
    }
  }

  record Classified(
      long nanos,
      long millis,
      String gapClass,
      Map<String, Integer> samples,
      String closedBy,
      String batch,
      long offsetMillis) {}

  record Result(
      long checks,
      long longestMillis,
      String longestClosedBy,
      long over250,
      long over1000,
      long over2000,
      long tailMillis,
      Classified longest,
      List<Classified> top,
      Classified longestCompile) {}
}
