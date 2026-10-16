package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class SpoonCorpusSpikeIT {

  private static final int SCHEMA = 1;
  private static final double MAX_PARSE_FAILURE_RATIO = 0.02;
  private static final long MAX_HEAP_BYTES = 2L * 1024 * 1024 * 1024;
  private static final int MIN_PASSING = 4;
  private static final int MAX_LISTED_FAILURES = 500;
  private static final String DERIVED = "broken-tree";
  private static final String DERIVED_FROM = "commons-lang";
  private static final List<String> REPOSITORIES =
      List.of("commons-lang", "junit5", "spring-framework", "elasticsearch", DERIVED);

  private static final Set<SourceRootKind> DECISION_KINDS =
      Set.of(SourceRootKind.MAIN, SourceRootKind.GENERATED);

  @Test
  void oSpoonSemClasspathSobreviveAoCorpusDentroDeDoisGibDeHeap() throws Exception {
    HermeticSystemReader.install();
    final Path corpus = corpusDirectory();
    final Path report = Path.of(System.getProperty("strata.spike.report")).toAbsolutePath();
    final List<SpikeReport.RepositoryResult> results = new ArrayList<>();

    for (final String name : REPOSITORIES) {
      results.add(measure(name, corpus.resolve(name)));
      write(report, reportOf(corpus, results, false));
    }
    final SpikeReport finalReport = reportOf(corpus, results, true);
    write(report, finalReport);

    final SpikeReport.RepositoryResult derived = results.getLast();
    assertThat(derived.parseFailures().stream().map(SpikeReport.Failure::path))
        .as("as mutações de MUTATIONS.txt são o piso conhecido: sem elas o spike não mede nada")
        .containsAll(mutatedFiles(corpus.resolve(DERIVED)));
    assertThat(finalReport.verdict().outcome())
        .as("a regra de decisão, aplicada mecanicamente; o relatório está em " + report)
        .isEqualTo("PASS");
  }

  private static SpikeReport.RepositoryResult measure(String name, Path directory)
      throws IOException {
    System.gc();
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    if (outcome instanceof RepositoryOutcome.Rejected<ValidatedRepository>(var reason)) {
      return refused(name, reason.code().name());
    }
    final ValidatedRepository repository =
        ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
    final Probe probe = new Probe();
    final CancellationGap gap = new CancellationGap();
    final long started = System.nanoTime();
    try (HeapPeakMeter meter = new HeapPeakMeter()) {
      final SourceAnalysis analysis;
      try {
        analysis =
            new SpoonModelBuilder()
                .analyze(repository, repository.layout().sourceRoots(), probe, gap);
      } catch (OutOfMemoryError exhausted) {
        return outOfMemory(name, repository, meter, millisSince(started));
      }
      return completed(
          name, directory, repository, analysis, meter, probe, gap, millisSince(started));
    }
  }

  private static SpikeReport.RepositoryResult completed(
      String name,
      Path directory,
      ValidatedRepository repository,
      SourceAnalysis analysis,
      HeapPeakMeter meter,
      Probe probe,
      CancellationGap gap,
      long wallMillis)
      throws IOException {
    final Map<SourceRootId, SourceRootKind> kinds = new LinkedHashMap<>();
    repository.layout().sourceRoots().forEach(root -> kinds.put(root.id(), root.kind()));
    final Map<SourceRootKind, int[]> counts = new EnumMap<>(SourceRootKind.class);
    final List<SpikeReport.Failure> failures = new ArrayList<>();
    final List<String> tooLarge = new ArrayList<>();
    for (final SourceFile file : analysis.files()) {
      final SourceRootKind kind = kinds.get(file.rootId());
      final int[] count = counts.computeIfAbsent(kind, ignored -> new int[4]);
      count[0]++;
      switch (file.status()) {
        case ANALYZED -> count[1]++;
        case SKIPPED_TOO_LARGE -> {
          count[2]++;
          tooLarge.add(file.relativePath());
        }
        case PARSE_FAILED -> {
          count[3]++;
          failures.add(
              new SpikeReport.Failure(file.relativePath(), kind.name(), file.cause().name()));
        }
      }
    }
    final SpikeReport.Ratio decision = ratio(counts, DECISION_KINDS);
    final SpikeReport.Ratio allRoots = ratio(counts, Set.of(SourceRootKind.values()));
    final SourceAnalysis.Diagnostics diagnostics = analysis.diagnostics();
    return new SpikeReport.RepositoryResult(
        name,
        DERIVED.equals(name) ? "derived" : "clone",
        repository.snapshot().head().commitId(),
        DERIVED.equals(name) ? DERIVED_FROM : null,
        DERIVED.equals(name) ? mutations(directory) : null,
        "COMPLETED",
        null,
        rootsByKind(repository),
        fileCounts(counts),
        decision,
        allRoots,
        decision.ratio() < MAX_PARSE_FAILURE_RATIO,
        failures.size(),
        failures.stream().limit(MAX_LISTED_FAILURES).toList(),
        tooLarge,
        new SpikeReport.Heap(meter.peakLiveBytes(), meter.peakUsedBytes()),
        new SpikeReport.Timing(
            wallMillis,
            diagnostics.preParseMillis(),
            diagnostics.buildMillis(),
            diagnostics.visitMillis(),
            diagnostics.longestBuildMillis(),
            diagnostics.longestBuildRoot(),
            gap.longestMillis()),
        new SpikeReport.Models(
            diagnostics.batches(), diagnostics.quarantinedBatches(), diagnostics.builds()),
        probe.extraction());
  }

  private static SpikeReport.RepositoryResult outOfMemory(
      String name, ValidatedRepository repository, HeapPeakMeter meter, long wallMillis) {
    return new SpikeReport.RepositoryResult(
        name,
        DERIVED.equals(name) ? "derived" : "clone",
        repository.snapshot().head().commitId(),
        DERIVED.equals(name) ? DERIVED_FROM : null,
        null,
        "OUT_OF_MEMORY",
        null,
        rootsByKind(repository),
        Map.of(),
        null,
        null,
        false,
        0,
        List.of(),
        List.of(),
        new SpikeReport.Heap(meter.peakLiveBytes(), meter.peakUsedBytes()),
        new SpikeReport.Timing(wallMillis, 0, 0, 0, 0, "", 0),
        null,
        null);
  }

  private static SpikeReport.RepositoryResult refused(String name, String code) {
    return new SpikeReport.RepositoryResult(
        name,
        DERIVED.equals(name) ? "derived" : "clone",
        null,
        DERIVED.equals(name) ? DERIVED_FROM : null,
        null,
        "REFUSED",
        code,
        Map.of(),
        Map.of(),
        null,
        null,
        false,
        0,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null);
  }

  private static SpikeReport reportOf(
      Path corpus, List<SpikeReport.RepositoryResult> results, boolean complete)
      throws IOException {
    final int passing = (int) results.stream().filter(SpikeReport.RepositoryResult::pass).count();
    final String outcome = !complete ? "INCOMPLETE" : passing >= MIN_PASSING ? "PASS" : "FAIL";
    return new SpikeReport(
        SCHEMA,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        baseCommit(corpus.getParent()),
        "Spoon in no-classpath mode over every detected source root of each corpus repository,"
            + " one model alive at a time, with a visitor that walks types, type references"
            + " (getTypeDeclaration) and executable references (getExecutableDeclaration) while"
            + " the model is alive",
        new SpikeReport.Rule(
            "SpoonCorpusSpikeIT",
            MAX_PARSE_FAILURE_RATIO,
            "parseFailures/totalFiles over the default analysis selection: every MAIN and"
                + " GENERATED root, no TEST root (includeTests=false); allRoots reports every"
                + " root and does not decide",
            MAX_HEAP_BYTES,
            "every source root modelled under -Xmx2g without OutOfMemoryError; peakLiveBytes"
                + " (largest heap occupancy after a collection) is the margin, and peakUsedBytes"
                + " cannot exceed the ceiling by construction",
            MIN_PASSING,
            REPOSITORIES.size(),
            8,
            "keep Spoon as the source analysis engine",
            "fall back to JavaParser: RESOLVED only where a single-type import or the same-package"
                + " rule fixes the name, everything else UNRESOLVED_EXTERNAL"),
        new SpikeReport.Verdict(
            outcome, passing, results.size(), passing == REPOSITORIES.size() && complete),
        environment(),
        new SpikeReport.SpoonSettings(
            System.getProperty("strata.spoon.version", "unknown"),
            SpoonModelBuilder.COMPLIANCE_LEVEL,
            true,
            true,
            false,
            "JDK platform modules of the running runtime plus the batch's own sources; never"
                + " the analyzer's classpath, never the repository's jars",
            "a fresh ECJ parser per file decides; Spoon's own filter stays on as a backstop",
            "module-info.java is modelled in a batch of its own"),
        List.copyOf(results));
  }

  private static SpikeReport.Environment environment() {
    final OperatingSystemMXBean system =
        ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    final List<String> flags =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(argument -> argument.startsWith("-X"))
            .toList();
    return new SpikeReport.Environment(
        System.getProperty("os.name") + " " + System.getProperty("os.version"),
        System.getProperty("os.arch"),
        System.getProperty("strata.host.cpu", "not recorded: pass -Dstrata.host.cpu"),
        Runtime.getRuntime().availableProcessors(),
        system.getTotalMemorySize(),
        System.getProperty("strata.host.storage", "not recorded: pass -Dstrata.host.storage"),
        "full JDK under Maven Surefire, not the jlink image the application ships",
        System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
        flags,
        Runtime.getRuntime().maxMemory(),
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .toList());
  }

  private static SpikeReport.Ratio ratio(
      Map<SourceRootKind, int[]> counts, Set<SourceRootKind> kinds) {
    int total = 0;
    int failed = 0;
    for (final Map.Entry<SourceRootKind, int[]> entry : counts.entrySet()) {
      if (kinds.contains(entry.getKey())) {
        total += entry.getValue()[0];
        failed += entry.getValue()[3];
      }
    }
    return new SpikeReport.Ratio(total, failed, total == 0 ? 0.0 : (double) failed / total);
  }

  private static Map<String, Integer> rootsByKind(ValidatedRepository repository) {
    final Map<String, Integer> roots = new LinkedHashMap<>();
    for (final SourceRootKind kind : SourceRootKind.values()) {
      roots.put(
          kind.name(),
          (int)
              repository.layout().sourceRoots().stream()
                  .map(SourceRoot::kind)
                  .filter(kind::equals)
                  .count());
    }
    return roots;
  }

  private static Map<String, SpikeReport.FileCounts> fileCounts(Map<SourceRootKind, int[]> counts) {
    final Map<String, SpikeReport.FileCounts> files = new LinkedHashMap<>();
    for (final SourceRootKind kind : SourceRootKind.values()) {
      final int[] count = counts.getOrDefault(kind, new int[4]);
      files.put(kind.name(), new SpikeReport.FileCounts(count[0], count[1], count[2], count[3]));
    }
    return files;
  }

  private static List<String> mutations(Path directory) throws IOException {
    return Files.readAllLines(directory.resolve("MUTATIONS.txt"), StandardCharsets.UTF_8).stream()
        .filter(line -> !line.isBlank() && !line.startsWith("#"))
        .toList();
  }

  private static List<String> mutatedFiles(Path directory) throws IOException {
    return mutations(directory).stream()
        .map(line -> line.substring(line.indexOf(' ')).trim())
        .map(path -> path.startsWith("./") ? path.substring(2) : path)
        .toList();
  }

  private static Path corpusDirectory() throws IOException {
    final Path corpus = Path.of(System.getProperty("strata.corpus"));
    assertThat(corpus.resolve(DERIVED))
        .as("o corpus não foi clonado: rode bash scripts/corpus.sh antes do spike")
        .isDirectory();
    return corpus.toRealPath();
  }

  private static String baseCommit(Path strataRoot) throws IOException {
    try (Repository strata =
        new FileRepositoryBuilder()
            .setGitDir(strataRoot.resolve(".git").toFile())
            .setMustExist(true)
            .build()) {
      return Optional.ofNullable(strata.resolve("HEAD"))
          .map(head -> head.abbreviate(7).name())
          .orElse("unborn");
    }
  }

  private static void write(Path report, SpikeReport content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static final class CancellationGap implements BooleanSupplier {

    private long last = System.nanoTime();
    private long longest;

    @Override
    public boolean getAsBoolean() {
      final long now = System.nanoTime();
      longest = Math.max(longest, now - last);
      last = now;
      return false;
    }

    private long longestMillis() {
      return longest / 1_000_000;
    }
  }

  private static final class Probe implements BatchVisitor {

    private long types;
    private long typeReferences;
    private long resolvedTypeReferences;
    private long executableReferences;
    private long resolvedExecutableReferences;

    @Override
    public void visit(ModeledBatch batch) {
      for (final long[] unit :
          batch.perCompilationUnit((file, unit) -> count(unit.getDeclaredTypes()))) {
        types += unit[0];
        typeReferences += unit[1];
        resolvedTypeReferences += unit[2];
        executableReferences += unit[3];
        resolvedExecutableReferences += unit[4];
      }
    }

    private static long[] count(List<CtType<?>> declaredTypes) {
      final long[] counts = new long[5];
      for (final CtType<?> type : declaredTypes) {
        counts[0] += type.getElements(new TypeFilter<>(CtType.class)).size();
        for (final CtTypeReference<?> reference :
            type.getElements(new TypeFilter<>(CtTypeReference.class))) {
          counts[1]++;
          if (reference.getTypeDeclaration() != null) {
            counts[2]++;
          }
        }
        for (final CtExecutableReference<?> reference :
            type.getElements(new TypeFilter<>(CtExecutableReference.class))) {
          counts[3]++;
          if (reference.getExecutableDeclaration() != null) {
            counts[4]++;
          }
        }
      }
      return counts;
    }

    private SpikeReport.Extraction extraction() {
      return new SpikeReport.Extraction(
          types,
          typeReferences,
          resolvedTypeReferences,
          executableReferences,
          resolvedExecutableReferences);
    }
  }
}
