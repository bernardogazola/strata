package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceCharsets;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "strata.spike", matches = "true")
final class LocClocAgreementSpikeIT {

  private static final int SCHEMA = 1;
  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final String REPORT = "benchmarks/loc-cloc.json";
  private static final String INPUT = "analyzer/target/loc-cloc";
  private static final String CLOC_VERSION = "2.10";
  private static final String CLOC_HEADER = "# cloc " + CLOC_VERSION;
  private static final String DERIVED = "broken-tree";
  private static final String DERIVED_FROM = "commons-lang";

  private static final List<String> REPOSITORIES =
      List.of("commons-lang", DERIVED, "junit5", "spring-framework", "elasticsearch");

  static final String DESYNC = "cloc docstring-tracker desync";

  static final String UNEXPLAINED = "unexplained";

  private static final int PRE_REGISTERED_FILES = 42_834;
  private static final int PRE_REGISTERED_AGREEING = 42_817;
  private static final long PRE_REGISTERED_DELTA = 606;

  private static final List<String> PRE_REGISTERED_DISAGREEING =
      List.of(
          "broken-tree/src/test/java/org/apache/commons/lang3/time/FastDateParserTest.java",
          "commons-lang/src/test/java/org/apache/commons/lang3/time/FastDateParserTest.java",
          "elasticsearch/build-tools-internal/src/main/java/org/elasticsearch/gradle/internal/doc/"
              + "RestTestsFromDocSnippetTask.java",
          "elasticsearch/libs/x-content/src/test/java/org/elasticsearch/xcontent/"
              + "ObjectParserTests.java",
          "elasticsearch/server/src/test/java/org/elasticsearch/cluster/coordination/"
              + "ClusterFormationFailureHelperTests.java",
          "elasticsearch/test/fixtures/s3-fixture/src/test/java/fixture/s3/S3HttpHandlerTests.java",
          "elasticsearch/x-pack/plugin/esql/qa/server/src/main/java/org/elasticsearch/xpack/esql/qa/"
              + "rest/AbstractExternalSourceSpecTestCase.java",
          "elasticsearch/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/expression/"
              + "function/scalar/math/CopySign.java",
          "elasticsearch/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/expression/"
              + "function/scalar/spatial/StGeohash.java",
          "elasticsearch/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/expression/"
              + "function/scalar/spatial/StGeohex.java",
          "elasticsearch/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/expression/"
              + "function/scalar/spatial/StGeotile.java",
          "elasticsearch/x-pack/plugin/esql/src/main/java/org/elasticsearch/xpack/esql/expression/"
              + "function/scalar/string/JsonExtract.java",
          "elasticsearch/x-pack/plugin/sql/src/test/java/org/elasticsearch/xpack/sql/parser/"
              + "SqlParserTests.java",
          "elasticsearch/x-pack/plugin/text-structure/src/test/java/org/elasticsearch/xpack/"
              + "textstructure/structurefinder/TimestampFormatFinderTests.java",
          "junit5/junit-jupiter-params/src/main/java/org/junit/jupiter/params/provider/"
              + "CsvSource.java",
          "junit5/jupiter-tests/src/test/java/org/junit/jupiter/params/provider/"
              + "CsvArgumentsProviderTests.java",
          "spring-framework/spring-test/src/main/java/org/springframework/test/context/support/"
              + "AbstractTestContextBootstrapper.java");

  private static final String CLOC_COMMAND =
      "find . -name .git -prune -o -type f -name '*.java' -print | cloc --list-file=- --by-file"
          + " --skip-uniqueness --timeout=0 --quiet --csv --csv-delimiter=TAB, inside each clone"
          + " (scripts/loc-cloc-agreement.sh)";

  private static final String MEASURE =
      "Every regular *.java file of the five corpus clones, .git pruned (the population of the"
          + " cloc goldens and of RepositoryWalker): LocCounter.of(SourceCharsets.decode(bytes))"
          + ".loc() against cloc 2.10's code count; a file cloc printed no row for is compared"
          + " with 0. A disagreement's cause is 'cloc docstring-tracker desync' only when an"
          + " emulation of cloc's docstring_rm_comments flag (cloc:8570) misreads a triple quote"
          + " against the scanner's own line classification; any other is 'unexplained'.";

  private static final Pattern SINGLE = Pattern.compile("(\"\"\"|''')(.*?)\\1", Pattern.DOTALL);

  private static final Pattern IN_BLOCK =
      Pattern.compile("/\\*.*?(\"\"\"|''').*?\\*/", Pattern.DOTALL);

  private static final Pattern AFTER_SLASHES = Pattern.compile("//.*?(\"\"\"|''')", Pattern.DOTALL);

  private static final Pattern TRIPLE = Pattern.compile("\"\"\"|'''");

  private static final String CLOC_WHITESPACE = " \t\r\f\u000B";

  @Test
  void oScannerConcordaComOCloc210EmTodoArquivoDoCorpusMenosOsDezessetePreRegistrados()
      throws IOException {
    HermeticSystemReader.install();
    final Path strataRoot = Path.of(System.getProperty("strata.root")).toAbsolutePath().normalize();
    final Path corpus = corpusDirectory();
    final Path input = strataRoot.resolve(INPUT);
    final List<RepositoryResult> repositories = new ArrayList<>();
    for (final String name : REPOSITORIES) {
      repositories.add(measure(name, corpus.resolve(name), input));
    }
    final Totals totals = totals(repositories);
    final Report report =
        new Report(
            SCHEMA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            headOf(strataRoot.toFile(), 7),
            MEASURE,
            new Cloc(CLOC_VERSION, CLOC_COMMAND, INPUT),
            Environment.ofThisJvm(),
            preRegistration(totals, repositories),
            totals,
            List.copyOf(repositories));
    write(strataRoot.resolve(REPORT), report);

    final SoftAssertions softly = new SoftAssertions();
    for (final RepositoryResult repository : repositories) {
      softly
          .assertThat(repository.listDifferences())
          .as(repository.name() + ": a lista do script é a população do clone, agora")
          .isEmpty();
    }
    softly
        .assertThat(report.preRegistration().differences())
        .as("o pré-registro: 42 817 de 42 834, os 17 nomeados, todos dessincronia, +606")
        .isEmpty();
    softly.assertAll();
  }

  private static RepositoryResult measure(String name, Path clone, Path input) throws IOException {
    final List<String> listed = Files.readAllLines(input.resolve(name + ".files"));
    final Map<String, Integer> cloc = clocRows(input.resolve(name + ".tsv"), listed);
    final List<String> listDifferences = listDifferences(listed, javaFiles(clone));
    final List<NoRow> noRow = new ArrayList<>();
    final List<Disagreement> disagreements = new ArrayList<>();
    long decodeNanos = 0;
    long scannerNanos = 0;
    long locScanner = 0;
    long locCloc = 0;
    int agreeing = 0;
    for (final String path : listed) {
      final byte[] bytes = Files.readAllBytes(clone.resolve(path));
      final long started = System.nanoTime();
      final char[] text = SourceCharsets.decode(bytes);
      final long decoded = System.nanoTime();
      final LocCounter counter = LocCounter.of(text);
      final int loc = counter.loc();
      scannerNanos += System.nanoTime() - decoded;
      decodeNanos += decoded - started;
      final Integer row = cloc.get(path);
      final int reference = row == null ? 0 : row;
      if (row == null) {
        noRow.add(new NoRow(path, bytes.length, loc));
      }
      if (loc == reference) {
        agreeing++;
      } else {
        disagreements.add(disagreement(path, loc, row, text, counter));
      }
      locScanner += loc;
      locCloc += reference;
    }
    final boolean derived = DERIVED.equals(name);
    return new RepositoryResult(
        name,
        derived ? "derived" : "clone",
        headOf(clone.toFile(), 0),
        derived ? DERIVED_FROM : null,
        listed.size(),
        listDifferences,
        agreeing,
        disagreements.size(),
        locScanner,
        locCloc,
        locCloc - locScanner,
        decodeNanos / NANOS_PER_MILLI,
        scannerNanos / NANOS_PER_MILLI,
        List.copyOf(noRow),
        List.copyOf(disagreements));
  }

  private static Disagreement disagreement(
      String path, int scanner, Integer cloc, char[] text, LocCounter counter) {
    final int delta = (cloc == null ? 0 : cloc) - scanner;
    final Optional<Misread> misread = firstMisread(text, counter);
    return new Disagreement(
        path,
        scanner,
        cloc,
        delta,
        misread.isPresent() ? DESYNC : UNEXPLAINED,
        misread.map(Misread::line).orElse(null),
        misread.map(Misread::trigger).orElse(null));
  }

  private static Map<String, Integer> clocRows(Path tsv, List<String> listed) throws IOException {
    final List<String> lines = Files.readAllLines(tsv);
    assertThat(lines).as(tsv + ": a primeira linha é a versão").first().isEqualTo(CLOC_HEADER);
    final Set<String> files = new HashSet<>(listed);
    final Map<String, Integer> rows = new HashMap<>();
    for (final String line : lines.subList(1, lines.size())) {
      final int tab = line.lastIndexOf('\t');
      final String path = line.substring(0, tab);
      assertThat(files).as(tsv + ": uma linha do cloc fora da lista").contains(path);
      assertThat(rows.put(path, Integer.valueOf(line.substring(tab + 1))))
          .as(tsv + ": um caminho repetido: " + path)
          .isNull();
    }
    return rows;
  }

  private static List<String> listDifferences(List<String> listed, List<String> walked) {
    final Set<String> fromScript = new LinkedHashSet<>(listed);
    final Set<String> fromClone = new LinkedHashSet<>(walked);
    final List<String> differences = new ArrayList<>();
    if (fromScript.size() != listed.size()) {
      differences.add("the list repeats a path");
    }
    fromScript.stream()
        .filter(path -> !fromClone.contains(path))
        .forEach(path -> differences.add("listed, not in the clone: " + path));
    fromClone.stream()
        .filter(path -> !fromScript.contains(path))
        .forEach(path -> differences.add("in the clone, not listed: " + path));
    return List.copyOf(differences.subList(0, Math.min(differences.size(), 20)));
  }

  private static List<String> javaFiles(Path clone) throws IOException {
    final List<String> files = new ArrayList<>();
    Files.walkFileTree(
        clone,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            return !dir.equals(clone) && ".git".equals(dir.getFileName().toString())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
              files.add(clone.relativize(file).toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files;
  }

  static Optional<Misread> firstMisread(char[] text, LocCounter counter) {
    if (hasLoneCr(text)) {
      return Optional.empty();
    }
    final String[] lines = new String(text).split("\n", -1);
    final int count =
        text.length > 0 && text[text.length - 1] == '\n' ? lines.length - 1 : lines.length;
    boolean inside = false;
    for (int index = 0; index < count; index++) {
      final String line = lines[index];
      if (isClocBlank(line)) {
        continue;
      }
      final int number = index + 1;
      if (SINGLE.matcher(line).find()) {
        continue;
      }
      if (IN_BLOCK.matcher(line).find() || AFTER_SLASHES.matcher(line).find()) {
        if (inside) {
          return Optional.of(new Misread(number, "closing \"\"\" after // or inside /* */"));
        }
        continue;
      }
      final Matcher triple = TRIPLE.matcher(line);
      if (!triple.find()) {
        continue;
      }
      if ("'''".equals(triple.group())) {
        return Optional.of(new Misread(number, "''' toggles the tracker"));
      }
      if (counter.loc(number, number) == 0) {
        return Optional.of(new Misread(number, "\"\"\" on a comment-only line toggles"));
      }
      inside = !inside;
    }
    return inside
        ? Optional.of(new Misread(count, "the tracker is still inside at end of file"))
        : Optional.empty();
  }

  private static boolean hasLoneCr(char[] text) {
    for (int index = 0; index < text.length; index++) {
      if (text[index] == '\r' && (index + 1 == text.length || text[index + 1] != '\n')) {
        return true;
      }
    }
    return false;
  }

  private static boolean isClocBlank(String line) {
    for (int index = 0; index < line.length(); index++) {
      if (CLOC_WHITESPACE.indexOf(line.charAt(index)) < 0) {
        return false;
      }
    }
    return true;
  }

  private static Totals totals(List<RepositoryResult> repositories) {
    return new Totals(
        repositories.stream().mapToInt(RepositoryResult::files).sum(),
        repositories.stream().mapToInt(RepositoryResult::agreeing).sum(),
        repositories.stream().mapToInt(RepositoryResult::disagreeing).sum(),
        repositories.stream().mapToInt(repository -> repository.clocNoRow().size()).sum(),
        repositories.stream().mapToLong(RepositoryResult::locScanner).sum(),
        repositories.stream().mapToLong(RepositoryResult::locCloc).sum(),
        repositories.stream().mapToLong(RepositoryResult::locDelta).sum(),
        repositories.stream().mapToLong(RepositoryResult::decodeMillis).sum(),
        repositories.stream().mapToLong(RepositoryResult::scannerMillis).sum());
  }

  private static PreRegistration preRegistration(
      Totals totals, List<RepositoryResult> repositories) {
    final List<String> differences = new ArrayList<>();
    if (totals.files() != PRE_REGISTERED_FILES) {
      differences.add("files: " + totals.files() + ", pre-registered " + PRE_REGISTERED_FILES);
    }
    if (totals.agreeing() != PRE_REGISTERED_AGREEING) {
      differences.add(
          "agreeing: " + totals.agreeing() + ", pre-registered " + PRE_REGISTERED_AGREEING);
    }
    final Set<String> expected = new LinkedHashSet<>(PRE_REGISTERED_DISAGREEING);
    long delta = 0;
    for (final RepositoryResult repository : repositories) {
      for (final Disagreement disagreement : repository.disagreements()) {
        final String path = repository.name() + "/" + disagreement.path();
        delta += disagreement.delta();
        if (!expected.remove(path)) {
          differences.add("not pre-registered: " + path + " delta " + disagreement.delta());
        }
        if (!DESYNC.equals(disagreement.cause())) {
          differences.add("cause of " + path + ": " + disagreement.cause());
        }
      }
    }
    expected.forEach(path -> differences.add("pre-registered, agrees: " + path));
    if (delta != PRE_REGISTERED_DELTA) {
      differences.add("delta: " + delta + " lines, pre-registered +" + PRE_REGISTERED_DELTA);
    }
    return new PreRegistration(
        PRE_REGISTERED_FILES,
        PRE_REGISTERED_AGREEING,
        PRE_REGISTERED_DISAGREEING,
        DESYNC,
        PRE_REGISTERED_DELTA,
        differences.isEmpty(),
        List.copyOf(differences));
  }

  private static Path corpusDirectory() throws IOException {
    final Path corpus = Path.of(System.getProperty("strata.corpus"));
    assertThat(corpus.resolve(DERIVED))
        .as("o corpus não foi clonado: rode bash scripts/corpus.sh antes do spike")
        .isDirectory();
    return corpus.toRealPath();
  }

  private static String headOf(File root, int length) throws IOException {
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder().addCeilingDirectory(root.getParentFile()).findGitDir(root);
    assertThat(builder.getGitDir()).as("não é um checkout Git: " + root).isNotNull();
    try (Repository repository = builder.setMustExist(true).build()) {
      return Optional.ofNullable(repository.resolve("HEAD"))
          .map(head -> length == 0 ? head.name() : head.abbreviate(length).name())
          .orElse("unborn");
    }
  }

  private static void write(Path report, Report content) throws IOException {
    final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final Path partial = report.resolveSibling(report.getFileName() + ".partial");
    Files.writeString(partial, mapper.writeValueAsString(content) + "\n", StandardCharsets.UTF_8);
    Files.move(
        partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  record Misread(int line, String trigger) {}

  record Report(
      int schema,
      String measuredAt,
      String baseCommit,
      String measure,
      Cloc cloc,
      Environment environment,
      PreRegistration preRegistration,
      Totals totals,
      List<RepositoryResult> repositories) {}

  record Cloc(String version, String command, String input) {}

  record Environment(
      String os,
      String arch,
      String cpu,
      int logicalCores,
      long ramBytes,
      String storage,
      String runtime,
      String jvm,
      List<String> jvmFlags,
      long maxHeapBytes,
      List<String> garbageCollectors,
      boolean assertionsEnabled) {

    static Environment ofThisJvm() {
      final OperatingSystemMXBean system =
          ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
      return new Environment(
          System.getProperty("os.name") + " " + System.getProperty("os.version"),
          System.getProperty("os.arch"),
          System.getProperty("strata.host.cpu", "not recorded: pass -Dstrata.host.cpu"),
          Runtime.getRuntime().availableProcessors(),
          system.getTotalMemorySize(),
          System.getProperty("strata.host.storage", "not recorded: pass -Dstrata.host.storage"),
          "full JDK under Maven Surefire, not the jlink image the application ships",
          System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
          ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
              .filter(argument -> argument.startsWith("-X"))
              .toList(),
          Runtime.getRuntime().maxMemory(),
          ManagementFactory.getGarbageCollectorMXBeans().stream()
              .map(GarbageCollectorMXBean::getName)
              .toList(),
          assertsRun());
    }

    private static boolean assertsRun() {
      boolean enabled = false;
      assert enabled = true;
      return enabled;
    }
  }

  record PreRegistration(
      int files,
      int agreeing,
      List<String> disagreeing,
      String cause,
      long deltaLines,
      boolean holds,
      List<String> differences) {}

  record Totals(
      int files,
      int agreeing,
      int disagreeing,
      int clocNoRow,
      long locScanner,
      long locCloc,
      long locDelta,
      long decodeMillis,
      long scannerMillis) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RepositoryResult(
      String name,
      String origin,
      String commitId,
      String derivedFrom,
      int files,
      List<String> listDifferences,
      int agreeing,
      int disagreeing,
      long locScanner,
      long locCloc,
      long locDelta,
      long decodeMillis,
      long scannerMillis,
      List<NoRow> clocNoRow,
      List<Disagreement> disagreements) {}

  record NoRow(String path, long bytes, int scanner) {}

  record Disagreement(
      String path,
      int scanner,
      Integer cloc,
      int delta,
      String cause,
      Integer misreadLine,
      String trigger) {}
}
