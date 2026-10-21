package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeMetric;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Complexity;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CyclomaticComplexityTest {

  private static final String SOURCE_ROOT = "src/main/java/";

  private static final int MIN_UNITS = 30;

  private static final String OPEN_PREFIX = "Open";

  private static final String NO_ELEMENT = "none";

  private static final int PERCENTILE = 90;

  private static final int PERCENT = 100;

  private static final int TENTHS = 10;

  private static final List<String> CONSTRUCTS =
      List.of(
          "if", "for", "foreach", "while", "do", "catch", "?:", "&&", "||", "case", "when",
          "assert");

  private static final List<String> ZEROS =
      List.of(
          "else",
          "default",
          "&",
          "|",
          "^",
          "&=",
          "|=",
          "^=",
          "try",
          "finally",
          "synchronized",
          "return",
          "throw",
          "label",
          "yield");

  private static final Pattern CC_LINE =
      Pattern.compile("^\\s*\\*\\s*CC = (?:(.+) = )?(\\d+|none)\\s*$");

  private static final Pattern INITIALIZERS_LINE =
      Pattern.compile("^\\s*\\*\\s*<initializers> (?:CC = (?:(.+) = )?(\\d+)|= none)\\s*$");

  private static final Pattern UNITS_LINE =
      Pattern.compile("^\\s*\\*\\s*units = \\[([\\d, ]*)]\\s*$");

  private static final Pattern KEY_LINE =
      Pattern.compile(
          "^\\s*\\*\\s*(ccSum|ccMax|ccMean|ccP90|unitCount|ccSumIncludingNested) = (.+?)\\s*$");

  private static final Pattern ZERO_LINE = Pattern.compile("^\\s*\\*\\s*zero: (.+?)\\s*$");

  private static final Pattern ELEMENT_LINE =
      Pattern.compile("^\\s*\\*\\s*element = (none|method:\\S+)\\s*$");

  private static final Pattern MACHINE_LINE =
      Pattern.compile(
          "^\\s*\\*\\s*(?:CC\\b|<initializers>|units\\b|ccSum|ccMax|ccMean|ccP90|unitCount"
              + "|zero:|element\\b)");

  private static final Pattern TERM =
      Pattern.compile(
          "^(?:(\\d+)\\*)?(if|foreach|for|while|do|catch|\\?:|&&|\\|\\||case|when|assert)$");

  private static final Pattern TYPE_DECLARATION =
      Pattern.compile("\\b(?:class|interface|enum|record|@interface) (\\w+)");

  private static final Pattern MEAN = Pattern.compile("^(\\d+) / (\\d+)$");

  private static final Pattern LAST_INTEGER = Pattern.compile("(\\d+)\\D*$");

  private static final List<String> KEYS =
      List.of("ccSum", "ccMax", "ccMean", "ccP90", "unitCount", "ccSumIncludingNested");

  @TempDir private static Path shared;

  private static Run cachedRun;

  @Test
  void oGoldenEhConsistentePeloTexto() throws IOException {
    final Golden golden = Golden.read();
    final Map<String, Integer> constructUse = new TreeMap<>();
    final Set<String> zeroUse = new HashSet<>();
    int units = 0;
    for (final HandFile file : golden.files()) {
      final List<Integer> lineValues = new ArrayList<>();
      for (final HandUnit unit : file.units()) {
        final Map<String, Integer> increments = increments(unit);
        if (unit.counts()) {
          lineValues.add(unit.value());
          if (file.core()) {
            increments.forEach((construct, k) -> constructUse.merge(construct, k, Integer::sum));
          }
        }
      }
      final List<Integer> typeValues = new ArrayList<>();
      for (final HandType type : file.types()) {
        aggregates(type, file);
        typeValues.addAll(type.units());
      }
      assertThat(sorted(lineValues))
          .as(file.name() + ": as linhas de unidade são exatamente as unidades dos tipos")
          .isEqualTo(sorted(typeValues));
      for (final ZeroLine zero : file.zeros()) {
        assertThat(ZEROS).as(zero.where() + ": fichas de peso zero").containsAll(zero.tokens());
        zeroUse.addAll(zero.tokens());
      }
      units += lineValues.size();
    }
    for (final String construct : CONSTRUCTS) {
      assertThat(constructUse.getOrDefault(construct, 0))
          .as("o construto " + construct + " em uma unidade do núcleo")
          .isPositive();
    }
    assertThat(zeroUse).as("todo construto de peso zero em uma linha zero:").containsAll(ZEROS);
    assertThat(units).as("unidades do golden").isGreaterThanOrEqualTo(MIN_UNITS);
  }

  @Test
  void oGoldenSePrendeAosElementosPelaLinha() throws Exception {
    final Run run = run();

    assertThat(run.analysis().files()).hasSize(goldenFiles().size());
    for (final SourceFile file : run.analysis().files()) {
      assertThat(file.status()).as(file.relativePath()).isEqualTo(SourceFileStatus.ANALYZED);
    }
    assertThat(run.sink().accepted()).as("o golden é um lote só").hasSize(1);
    final Binding binding = bind(Golden.read(), run);
    assertThat(binding.types()).hasSameSizeAs(run.types());
  }

  @Test
  void aCcEmitidaEhABijecaoDoGolden() throws Exception {
    final Run run = run();

    bijection(Golden.read(), run, run.metrics());
  }

  @Test
  void umMetodoDeInterfaceNaoTemUnidade() throws Exception {
    final Run run = run();

    assertThat(cc(run, "method:Shape#area()")).as("Shape#area()").isNull();
    assertThat(cc(run, "method:Shape#name()")).as("Shape#name()").isNull();
    assertThat(cc(run, "method:Named#label()")).as("Named#label()").isNull();
    assertThat(cc(run, "type:Shape")).isEqualTo(new Complexity(7, 3, 3, 3));
    assertThat(cc(run, "type:Named")).isEqualTo(Complexity.NONE);
  }

  @Test
  void asDecisoesDeUmLambdaPesamNoMetodoQueOEnvolve() throws Exception {
    final Run run = run();

    assertThat(single(cc(run, "method:Lambdas#shorten(java.util.List)"))).isEqualTo(4);
    assertThat(single(cc(run, "method:Lambdas#curried()"))).isEqualTo(4);
    assertThat(single(cc(run, "method:Dispatcher#applyAsInt(int)"))).isEqualTo(3);
    assertThat(single(cc(run, "method:Dispatcher#mirror()"))).isEqualTo(1);
    assertThat(single(cc(run, "method:Lambdas#deferred(boolean)"))).isEqualTo(2);
    assertThat(single(cc(run, "method:Lambdas#run()"))).isEqualTo(2);
  }

  @Test
  void umMetodoDeClasseAnonimaEhUnidadePropria() throws Exception {
    final Run run = run();

    assertThat(single(cc(run, "method:Dispatcher#later(boolean)"))).isEqualTo(2);
    final Complexity merged = cc(run, "method:Dispatcher#run()");
    assertThat(merged).as("Dispatcher#run()").isNotNull();
    assertThat(List.of(merged.sum(), merged.max(), merged.units())).containsExactly(5, 3, 2);
    assertThat(cc(run, "type:Dispatcher")).isEqualTo(new Complexity(21, 3, 3, 10));
  }

  @Test
  void umaGuardaSetaDeslocadaContaMaisUm() throws Exception {
    final Run run = run();

    assertThat(single(cc(run, "method:Guards#arrowInvocation(java.lang.Object)"))).isEqualTo(4);
    assertThat(single(cc(run, "method:Guards#colonInvocation(java.lang.Object)"))).isEqualTo(3);
  }

  private static void bijection(Golden golden, Run run, List<CodeMetric> emitted) {
    final Binding binding = bind(golden, run);
    final List<CodeElementId> elementIds = new ArrayList<>();
    run.types().forEach(type -> elementIds.add(type.id()));
    run.methods().forEach(method -> elementIds.add(method.id()));

    assertThat(emitted.stream().map(CodeMetric::element).toList())
        .as("uma CodeMetric por TYPE e por METHOD do lote, nenhuma a mais")
        .containsExactlyInAnyOrderElementsOf(elementIds);
    final Map<CodeElementId, CodeMetric> metrics = metricsById(emitted);

    for (final MethodElement method : run.methods()) {
      final List<Integer> merged = mergedInto(binding, method.id());
      final Complexity complexity = metrics.get(method.id()).complexity();
      if (merged.isEmpty()) {
        assertThat(complexity)
            .as(method.id() + ": nenhuma declaração dele é unidade, então sem valor de CC")
            .isNull();
      } else {
        assertThat(complexity).as(method.id() + ": as unidades " + merged).isNotNull();
        assertThat(List.of(complexity.sum(), complexity.max(), complexity.units()))
            .as(method.id() + ": (soma, máximo, unidades) de " + merged)
            .containsExactly(sum(merged), max(merged), merged.size());
      }
    }

    int emittedUnits = 0;
    for (final Map.Entry<HandType, TypeElement> entry : binding.types().entrySet()) {
      final HandType hand = entry.getKey();
      final CodeElementId id = entry.getValue().id();
      final Complexity complexity = metrics.get(id).complexity();
      assertThat(complexity)
          .as(hand.where() + ": (ccSum, ccMax, ccP90, unitCount)")
          .isEqualTo(new Complexity(hand.sum(), hand.max(), hand.p90(), hand.unitCount()));
      assertThat(complexity.mean())
          .as(hand.where() + ": ccMean = " + hand.key("ccMean"))
          .isEqualTo(hand.mean());
      assertThat(includingNested(id, metrics))
          .as(hand.where() + ": ccSumIncludingNested = " + hand.key("ccSumIncludingNested"))
          .isEqualTo(hand.includingNested());
      assertThat(methodSums(id, run, metrics) + elementless(id, binding))
          .as(hand.where() + ": Σ cc_sum(METHOD) + Σ unidades sem elemento = ccSum")
          .isEqualTo(hand.sum());
      emittedUnits += complexity.units();
    }
    assertThat(emittedUnits)
        .as("as unidades do golden, cada uma uma vez")
        .isEqualTo((int) golden.units().stream().filter(HandUnit::counts).count());
  }

  private static Map<String, Integer> increments(HandUnit unit) {
    final Map<String, Integer> counts = new TreeMap<>();
    if (!unit.counts()) {
      return counts;
    }
    if (unit.arithmetic() == null) {
      assertThat(unit.value()).as(unit.where() + ": CC sem conta só vale 1").isEqualTo(1);
      return counts;
    }
    final String[] terms = unit.arithmetic().split(" \\+ ", -1);
    assertThat(terms[0]).as(unit.where() + ": a conta começa pela base 1").isEqualTo("1");
    for (int index = 1; index < terms.length; index++) {
      final Matcher term = TERM.matcher(terms[index]);
      assertThat(term.matches()).as(unit.where() + ": a ficha " + terms[index]).isTrue();
      final int times = term.group(1) == null ? 1 : Integer.parseInt(term.group(1));
      counts.merge(term.group(2), times, Integer::sum);
    }
    assertThat(1 + sum(List.copyOf(counts.values())))
        .as(unit.where() + ": 1 + Σk = " + unit.value())
        .isEqualTo(unit.value());
    return counts;
  }

  private static void aggregates(HandType type, HandFile file) {
    final String where = type.where();
    final List<Integer> units = type.units();
    final int n = units.size();
    assertThat(units).as(where + ": units em ordem crescente").isSorted();
    assertThat(type.key("unitCount")).as(where + ": unitCount").isEqualTo(String.valueOf(n));
    assertThat(type.key("ccMax")).as(where + ": ccMax").isEqualTo(String.valueOf(max(units)));
    final String expectedSum =
        n < 2
            ? String.valueOf(sum(units))
            : String.join(" + ", units.stream().map(String::valueOf).toList()) + " = " + sum(units);
    assertThat(type.key("ccSum")).as(where + ": ccSum").isEqualTo(expectedSum);
    assertThat(type.key("ccMean"))
        .as(where + ": ccMean, uma fração que o teste recalcula")
        .isEqualTo(n == 0 ? "0" : sum(units) + " / " + n);
    assertThat(type.key("ccP90"))
        .as(where + ": ccP90 por posto mais próximo")
        .isEqualTo(p90(units));
    for (final HandUnit unit : file.units()) {
      if (unit.kind() == UnitKind.TYPE_INITIALIZERS && unit.javadocEnd() == type.javadocEnd()) {
        assertThat(units)
            .as(where + ": o <initializers> é uma das unidades")
            .contains(unit.value());
      }
    }
    nestedDerivation(type, file);
  }

  private static void nestedDerivation(HandType type, HandFile file) {
    final String where = type.where();
    final List<Integer> units = type.units();
    final String[] total = type.key("ccSumIncludingNested").split(" = ", -1);
    final String[] terms = total[0].split(" \\+ ", -1);
    assertThat(terms[0])
        .as(where + ": ccSumIncludingNested começa pelo ccSum")
        .isEqualTo(String.valueOf(sum(units)));
    int including = sum(units);
    for (int index = 1; index < terms.length; index++) {
      final String[] nested = terms[index].split(" ", -1);
      final HandType inner = file.type(nested[0]);
      assertThat(inner).as(where + ": o aninhado " + nested[0]).isNotNull();
      assertThat(Integer.parseInt(nested[1]))
          .as(where + ": o total de " + nested[0])
          .isEqualTo(inner.includingNested());
      including += Integer.parseInt(nested[1]);
    }
    assertThat(total)
        .as(where + ": a soma só existe com aninhados")
        .hasSize(terms.length > 1 ? 2 : 1);
    assertThat(type.includingNested()).as(where + ": ccSumIncludingNested").isEqualTo(including);
  }

  private static String p90(List<Integer> ascending) {
    final int n = ascending.size();
    if (n == 0) {
      return "0";
    }
    final int scaled = PERCENTILE * n;
    final int rank = (scaled + PERCENT - 1) / PERCENT;
    return "units[ceil(%d*%d/%d)] = units[ceil(%d.%d)] = units[%d] = %d"
        .formatted(
            PERCENTILE,
            n,
            PERCENT,
            scaled / PERCENT,
            scaled % PERCENT / TENTHS,
            rank,
            ascending.get(rank - 1));
  }

  private static Binding bind(Golden golden, Run run) {
    final Map<String, TypeElement> typeAt = new HashMap<>();
    for (final TypeElement type : run.types()) {
      assertThat(type.lines().available()).as(type.id() + ": um tipo elemento tem linhas").isTrue();
      assertThat(typeAt.put(at(type.filePath(), type.lines().start()), type))
          .as(type.id() + ": um tipo por linha")
          .isNull();
    }
    final Map<CodeElementId, MethodElement> methods = new HashMap<>();
    for (final MethodElement method : run.methods()) {
      assertThat(methods.put(method.id(), method))
          .as(method.id() + ": um elemento por id")
          .isNull();
    }
    final Map<HandType, TypeElement> types = bindTypes(golden, run, typeAt);
    final Map<HandUnit, CodeElementId> elements = bindUnits(golden, run, methods);
    final Map<HandUnit, CodeElementId> owners = new LinkedHashMap<>();
    for (final HandUnit unit : golden.units()) {
      if (!unit.counts()) {
        continue;
      }
      final CodeElementId owner;
      if (elements.containsKey(unit)) {
        owner = methods.get(elements.get(unit)).declaringTypeId();
      } else if (unit.kind() == UnitKind.TYPE_INITIALIZERS) {
        owner = typeAt.get(at(SOURCE_ROOT + unit.file(), unit.javadocEnd() + 1)).id();
      } else {
        owner = innermost(run.types(), unit).id();
      }
      owners.put(unit, owner);
    }
    for (final Map.Entry<HandType, TypeElement> entry : types.entrySet()) {
      final CodeElementId id = entry.getValue().id();
      final List<Integer> owned = new ArrayList<>();
      owners.forEach(
          (unit, owner) -> {
            if (owner.equals(id)) {
              owned.add(unit.value());
            }
          });
      assertThat(sorted(owned))
          .as(entry.getKey().where() + ": as unidades que o modelo de elementos dá a " + id)
          .isEqualTo(sorted(entry.getKey().units()));
    }
    return new Binding(types, elements, owners);
  }

  private static Map<HandType, TypeElement> bindTypes(
      Golden golden, Run run, Map<String, TypeElement> typeAt) {
    final Map<HandType, TypeElement> types = new LinkedHashMap<>();
    for (final HandType type : golden.types()) {
      final TypeElement element = typeAt.get(at(SOURCE_ROOT + type.file(), type.javadocEnd() + 1));
      assertThat(element)
          .as(type.where() + ": o tipo elemento que começa na linha seguinte ao Javadoc")
          .isNotNull();
      types.put(type, element);
    }
    assertThat(types.values().stream().map(TypeElement::id).toList())
        .as("todo tipo elemento do lote tem os agregados no Javadoc, e só ele")
        .containsExactlyInAnyOrderElementsOf(run.types().stream().map(TypeElement::id).toList());
    return types;
  }

  private static Map<HandUnit, CodeElementId> bindUnits(
      Golden golden, Run run, Map<CodeElementId, MethodElement> methods) {
    final Map<String, HandUnit> declarationAt = new HashMap<>();
    for (final HandUnit unit : golden.units()) {
      if (unit.kind() == UnitKind.EXECUTABLE || unit.kind() == UnitKind.NO_UNIT) {
        declarationAt.put(at(SOURCE_ROOT + unit.file(), unit.javadocEnd() + 1), unit);
      }
    }
    final Map<HandUnit, CodeElementId> elements = new LinkedHashMap<>();
    for (final MethodElement method : run.methods()) {
      if (!method.lines().available()) {
        continue;
      }
      final HandUnit unit = declarationAt.get(at(method.filePath(), method.lines().start()));
      assertThat(unit)
          .as(method.id() + ": a linha CC cujo Javadoc fecha logo antes do elemento")
          .isNotNull();
      assertThat(unit.element())
          .as(unit.where() + ": a declaração cujas linhas " + method.id() + " carrega")
          .isNull();
      assertThat(elements.put(unit, method.id())).as(unit.where() + ": um elemento").isNull();
    }
    for (final HandUnit unit : golden.units()) {
      if (!declarationAt.containsValue(unit)) {
        continue;
      }
      if (unit.element() == null) {
        assertThat(elements)
            .as(unit.where() + ": sem element =, a linha se prende pela declaração")
            .containsKey(unit);
      } else if (!NO_ELEMENT.equals(unit.element())) {
        final CodeElementId id = new CodeElementId(unit.element());
        assertThat(methods).as(unit.where() + ": element = " + id).containsKey(id);
        elements.put(unit, id);
      }
    }
    return elements;
  }

  private static TypeElement innermost(List<TypeElement> types, HandUnit unit) {
    TypeElement innermost = null;
    for (final TypeElement type : types) {
      final boolean contains =
          type.filePath().equals(SOURCE_ROOT + unit.file())
              && type.lines().start() <= unit.javadocEnd()
              && unit.javadocEnd() <= type.lines().end();
      if (contains && (innermost == null || type.lines().start() > innermost.lines().start())) {
        innermost = type;
      }
    }
    assertThat(innermost).as(unit.where() + ": o tipo elemento que contém a linha").isNotNull();
    return innermost;
  }

  private static List<Integer> mergedInto(Binding binding, CodeElementId method) {
    final List<Integer> merged = new ArrayList<>();
    binding
        .elements()
        .forEach(
            (unit, element) -> {
              if (element.equals(method) && unit.counts()) {
                merged.add(unit.value());
              }
            });
    return merged;
  }

  private static int includingNested(CodeElementId type, Map<CodeElementId, CodeMetric> metrics) {
    final String nested = type.value() + "$";
    int total = 0;
    for (final CodeMetric metric : metrics.values()) {
      final String id = metric.element().value();
      if (id.equals(type.value()) || id.startsWith(nested)) {
        total += metric.complexity().sum();
      }
    }
    return total;
  }

  private static int methodSums(
      CodeElementId type, Run run, Map<CodeElementId, CodeMetric> metrics) {
    int total = 0;
    for (final MethodElement method : run.methods()) {
      final Complexity complexity = metrics.get(method.id()).complexity();
      if (method.declaringTypeId().equals(type) && complexity != null) {
        total += complexity.sum();
      }
    }
    return total;
  }

  private static int elementless(CodeElementId type, Binding binding) {
    int total = 0;
    for (final Map.Entry<HandUnit, CodeElementId> entry : binding.owners().entrySet()) {
      if (entry.getValue().equals(type) && !binding.elements().containsKey(entry.getKey())) {
        total += entry.getKey().value();
      }
    }
    return total;
  }

  private static Complexity cc(Run run, String id) {
    final CodeElementId element = new CodeElementId(id);
    final List<CodeElementId> ids = new ArrayList<>();
    run.types().forEach(type -> ids.add(type.id()));
    run.methods().forEach(method -> ids.add(method.id()));
    assertThat(ids).as(id + " é um elemento do lote").contains(element);
    final CodeMetric metric = metricsById(run.metrics()).get(element);
    assertThat(metric).as(id + ": a CodeMetric dele").isNotNull();
    return metric.complexity();
  }

  private static int single(Complexity complexity) {
    assertThat(complexity).as("um método com corpo tem valor de CC").isNotNull();
    assertThat(complexity.units()).as("uma unidade").isEqualTo(1);
    assertThat(complexity.max()).as("máximo de uma unidade").isEqualTo(complexity.sum());
    return complexity.sum();
  }

  private static Map<CodeElementId, CodeMetric> metricsById(List<CodeMetric> emitted) {
    final Map<CodeElementId, CodeMetric> metrics = new HashMap<>();
    for (final CodeMetric metric : emitted) {
      assertThat(metrics.put(metric.element(), metric))
          .as(metric.element() + ": uma métrica por elemento")
          .isNull();
    }
    return metrics;
  }

  private static synchronized Run run() throws Exception {
    if (cachedRun == null) {
      final Map<String, String> files = new TreeMap<>();
      for (final Path source : goldenFiles()) {
        files.put(
            SOURCE_ROOT + source.getFileName(), Files.readString(source, StandardCharsets.UTF_8));
      }
      final ValidatedRepository repository =
          SourceTrees.repository(shared.resolve("cc-golden"), files);
      final List<SourceRoot> roots = repository.layout().sourceRoots();
      final RecordingStructureSink sink = new RecordingStructureSink();
      final SourceAnalysis analysis =
          new SpoonModelBuilder()
              .analyze(repository, roots, new StructureExtractor(roots, sink), SourceTrees.NEVER);
      cachedRun = new Run(analysis, sink);
    }
    return cachedRun;
  }

  private static List<Path> goldenFiles() throws IOException {
    final Path directory = TestRepositories.corpusRoot().resolveSibling("cc-golden");
    try (Stream<Path> listing = Files.list(directory)) {
      return listing
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .toList();
    }
  }

  private static String at(String filePath, int line) {
    return filePath + ":" + line;
  }

  private static int sum(List<Integer> values) {
    return values.stream().mapToInt(Integer::intValue).sum();
  }

  private static int max(List<Integer> values) {
    return values.stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  private static List<Integer> sorted(List<Integer> values) {
    return values.stream().sorted().toList();
  }

  private static int lastInteger(String where, String text) {
    final Matcher matcher = LAST_INTEGER.matcher(text);
    assertThat(matcher.find()).as(where + ": um inteiro em " + text).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  private record Run(SourceAnalysis analysis, RecordingStructureSink sink) {

    List<TypeElement> types() {
      return sink.accepted().stream().flatMap(batch -> batch.elements().types().stream()).toList();
    }

    List<MethodElement> methods() {
      return sink.accepted().stream()
          .flatMap(batch -> batch.elements().methods().stream())
          .toList();
    }

    List<CodeMetric> metrics() {
      return sink.accepted().stream().flatMap(batch -> batch.metrics().stream()).toList();
    }
  }

  private record Binding(
      Map<HandType, TypeElement> types,
      Map<HandUnit, CodeElementId> elements,
      Map<HandUnit, CodeElementId> owners) {}

  private enum UnitKind {
    EXECUTABLE,
    NO_UNIT,
    TYPE_INITIALIZERS,
    FIELD_INITIALIZERS
  }

  private record HandUnit(
      String file, int javadocEnd, UnitKind kind, int value, String arithmetic, String element) {

    boolean counts() {
      return kind != UnitKind.NO_UNIT;
    }

    String where() {
      return file + ":" + javadocEnd;
    }
  }

  private record HandType(
      String file, int javadocEnd, String name, List<Integer> units, Map<String, String> keys) {

    String key(String key) {
      return keys.get(key);
    }

    int sum() {
      return lastInteger(where(), key("ccSum"));
    }

    int max() {
      return lastInteger(where(), key("ccMax"));
    }

    int p90() {
      return lastInteger(where(), key("ccP90"));
    }

    int unitCount() {
      return lastInteger(where(), key("unitCount"));
    }

    int includingNested() {
      return lastInteger(where(), key("ccSumIncludingNested"));
    }

    double mean() {
      final Matcher fraction = MEAN.matcher(key("ccMean"));
      if (!fraction.matches()) {
        assertThat(key("ccMean")).as(where() + ": ccMean").isEqualTo("0");
        return 0;
      }
      return (double) Integer.parseInt(fraction.group(1)) / Integer.parseInt(fraction.group(2));
    }

    String where() {
      return file + ":" + name;
    }
  }

  private record ZeroLine(String where, List<String> tokens) {}

  private record HandFile(
      String name, List<HandUnit> units, List<HandType> types, List<ZeroLine> zeros) {

    boolean core() {
      return !name.startsWith(OPEN_PREFIX);
    }

    HandType type(String simpleName) {
      return types.stream().filter(type -> type.name().equals(simpleName)).findFirst().orElse(null);
    }
  }

  private record Golden(List<HandFile> files) {

    static Golden read() throws IOException {
      final List<HandFile> files = new ArrayList<>();
      for (final Path path : goldenFiles()) {
        files.add(parse(path));
      }
      return new Golden(List.copyOf(files));
    }

    List<HandUnit> units() {
      return files.stream().flatMap(file -> file.units().stream()).toList();
    }

    List<HandType> types() {
      return files.stream().flatMap(file -> file.types().stream()).toList();
    }
  }

  private static HandFile parse(Path path) throws IOException {
    final String name = path.getFileName().toString();
    final List<String> lines = Files.readString(path, StandardCharsets.UTF_8).lines().toList();
    final List<HandUnit> units = new ArrayList<>();
    final List<HandType> types = new ArrayList<>();
    final List<ZeroLine> zeros = new ArrayList<>();
    int open = -1;
    for (int index = 0; index < lines.size(); index++) {
      final String line = lines.get(index).strip();
      if (open < 0 && line.startsWith("/**")) {
        open = index;
      }
      if (open >= 0 && line.endsWith("*/")) {
        final Javadoc javadoc =
            new Javadoc(name, index + 1, lines.subList(open, index + 1), declaration(lines, index));
        javadoc.read(units, types, zeros);
        open = -1;
      }
    }
    return new HandFile(name, List.copyOf(units), List.copyOf(types), List.copyOf(zeros));
  }

  private static String declaration(List<String> lines, int javadocEndIndex) {
    for (int index = javadocEndIndex + 1; index < lines.size(); index++) {
      final String line = lines.get(index).strip();
      if (!line.isEmpty() && (!line.startsWith("@") || line.startsWith("@interface"))) {
        return line;
      }
    }
    return "";
  }

  private record Javadoc(String file, int end, List<String> lines, String declaration) {

    void read(List<HandUnit> units, List<HandType> types, List<ZeroLine> zeros) {
      final List<Matcher> cc = matching(CC_LINE);
      final List<Matcher> initializers = matching(INITIALIZERS_LINE);
      final List<Matcher> unitsLines = matching(UNITS_LINE);
      final List<Matcher> elements = matching(ELEMENT_LINE);
      final List<Matcher> zeroLines = matching(ZERO_LINE);
      final Map<String, String> keys = new LinkedHashMap<>();
      for (final Matcher key : matching(KEY_LINE)) {
        assertThat(keys.put(key.group(1), key.group(2))).as(where() + ": " + key.group(1)).isNull();
      }
      for (final String line : lines) {
        final boolean known =
            Stream.of(CC_LINE, INITIALIZERS_LINE, UNITS_LINE, KEY_LINE, ZERO_LINE, ELEMENT_LINE)
                .anyMatch(pattern -> pattern.matcher(line).matches());
        assertThat(!MACHINE_LINE.matcher(line).find() || known)
            .as(where() + ": linha da gramática mal escrita: " + line.strip())
            .isTrue();
      }
      for (final Matcher zero : zeroLines) {
        zeros.add(new ZeroLine(where(), List.of(zero.group(1).split(" ", -1))));
      }
      if (!unitsLines.isEmpty()) {
        assertThat(cc.size() + elements.size() + zeroLines.size())
            .as(where() + ": Javadoc de tipo sem linha de unidade")
            .isZero();
        assertThat(unitsLines).as(where() + ": units =").hasSize(1);
        assertThat(initializers).as(where() + ": <initializers>").hasSize(1);
        assertThat(keys.keySet())
            .as(where() + ": agregados")
            .containsExactlyInAnyOrderElementsOf(KEYS);
        final Matcher typeName = TYPE_DECLARATION.matcher(declaration);
        assertThat(typeName.find()).as(where() + ": a declaração de tipo").isTrue();
        if (initializers.getFirst().group(2) != null) {
          units.add(initializersUnit(UnitKind.TYPE_INITIALIZERS, initializers.getFirst()));
        }
        types.add(
            new HandType(
                file, end, typeName.group(1), integers(unitsLines.getFirst().group(1)), keys));
        return;
      }
      assertThat(keys).as(where() + ": agregados fora de um Javadoc de tipo").isEmpty();
      if (!cc.isEmpty()) {
        assertThat(cc).as(where() + ": uma linha CC").hasSize(1);
        assertThat(initializers).as(where() + ": <initializers> em uma unidade").isEmpty();
        assertThat(elements.size()).as(where() + ": element =").isLessThanOrEqualTo(1);
        assertThat(zeroLines.size()).as(where() + ": zero:").isLessThanOrEqualTo(1);
        assertThat(CC_LINE.matcher(lastLine()).matches())
            .as(where() + ": a linha CC é a última do Javadoc")
            .isTrue();
        final Matcher line = cc.getFirst();
        final String element = elements.isEmpty() ? null : elements.getFirst().group(1);
        if ("none".equals(line.group(2))) {
          assertThat(line.group(1)).as(where() + ": CC = none sem conta").isNull();
          assertThat(element).as(where() + ": CC = none sem element =").isNull();
          units.add(new HandUnit(file, end, UnitKind.NO_UNIT, 0, null, null));
        } else {
          units.add(
              new HandUnit(
                  file,
                  end,
                  UnitKind.EXECUTABLE,
                  Integer.parseInt(line.group(2)),
                  line.group(1),
                  element));
        }
        return;
      }
      if (!initializers.isEmpty()) {
        assertThat(initializers).as(where() + ": um <initializers> de campo").hasSize(1);
        assertThat(elements.size() + zeroLines.size())
            .as(where() + ": só o <initializers> em um Javadoc de campo")
            .isZero();
        assertThat(initializers.getFirst().group(2))
            .as(where() + ": o <initializers> de campo só existe com decisão")
            .isNotNull();
        assertThat(INITIALIZERS_LINE.matcher(lastLine()).matches())
            .as(where() + ": o <initializers> é a última linha do Javadoc")
            .isTrue();
        units.add(initializersUnit(UnitKind.FIELD_INITIALIZERS, initializers.getFirst()));
        return;
      }
      assertThat(elements.size() + zeroLines.size())
          .as(where() + ": element = e zero: só em uma unidade")
          .isZero();
    }

    private HandUnit initializersUnit(UnitKind kind, Matcher line) {
      return new HandUnit(file, end, kind, Integer.parseInt(line.group(2)), line.group(1), null);
    }

    private List<Matcher> matching(Pattern pattern) {
      final List<Matcher> found = new ArrayList<>();
      for (final String line : lines) {
        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
          found.add(matcher);
        }
      }
      return found;
    }

    private String lastLine() {
      return lines.size() < 2 ? "" : lines.get(lines.size() - 2);
    }

    private static List<Integer> integers(String commaSeparated) {
      final List<Integer> values = new ArrayList<>();
      for (final String value : commaSeparated.split(",", -1)) {
        if (!value.isBlank()) {
          values.add(Integer.parseInt(value.strip()));
        }
      }
      return List.copyOf(values);
    }

    private String where() {
      return file + ":" + end;
    }
  }
}
