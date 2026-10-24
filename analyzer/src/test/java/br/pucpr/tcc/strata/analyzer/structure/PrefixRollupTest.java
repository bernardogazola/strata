package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class PrefixRollupTest {

  private static final String RESOURCES = "/structure/";

  private static Set<String> packages(String... names) {
    return Set.of(names);
  }

  private static Set<String> numbered(String prefix, int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> String.format("%s.p%03d", prefix, i))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> union(Collection<Set<String>> parts) {
    final Set<String> all = new HashSet<>();
    parts.forEach(all::addAll);
    return Set.copyOf(all);
  }

  private static Map<PackageGroup, Long> sizes(Map<String, PackageGroup> groups) {
    return groups.values().stream()
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  private static void assertGroup(
      Map<String, PackageGroup> groups, Collection<String> members, PackageGroup expected) {
    for (final String member : members) {
      assertThat(groups.get(member)).as(member).isEqualTo(expected);
    }
  }

  private static void assertExact(Map<String, PackageGroup> groups, Collection<String> members) {
    for (final String member : members) {
      assertThat(groups.get(member)).as(member).isEqualTo(PackageGroup.exact(member));
    }
  }

  @Test
  void quandoTudoCabeTodoPacoteEOSeuGrupoExato() {
    final Set<String> multiModule =
        packages(
            "br.pucpr.legacy.api",
            "br.pucpr.legacy.core",
            "br.pucpr.legacy.core.gen",
            "br.pucpr.legacy.core.spi");

    final Map<String, PackageGroup> groups = PrefixRollup.groups(multiModule, 300);

    assertThat(groups).hasSize(4);
    assertExact(groups, multiModule);
  }

  @Test
  void umEnsaioDeExatamenteOTetoEAceito() {
    final Set<String> chain = packages("a", "a.b", "a.b.c");

    final Map<String, PackageGroup> groups = PrefixRollup.groups(chain, 3);

    assertExact(groups, chain);
  }

  @Test
  void oMundoDe603PacotesDaPfxAPfxBEPkgC() {
    final Set<String> a = numbered("a", 301);
    final Set<String> b = numbered("b", 301);
    final Set<String> world = union(List.of(a, b, Set.of("c")));

    final Map<String, PackageGroup> groups = PrefixRollup.groups(world, 300);

    assertThat(groups).hasSize(603);
    assertThat(sizes(groups))
        .containsOnly(
            Map.entry(PackageGroup.prefix("a"), 301L),
            Map.entry(PackageGroup.prefix("b"), 301L),
            Map.entry(PackageGroup.exact("c"), 1L));
  }

  @Test
  void oMundoDe603PacotesA303DivideSoOPrimeiro() {
    final Set<String> a = numbered("a", 301);
    final Set<String> b = numbered("b", 301);

    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(union(List.of(a, b, Set.of("c"))), 303);

    assertThat(sizes(groups)).hasSize(303);
    assertExact(groups, a);
    assertGroup(groups, b, PackageGroup.prefix("b"));
    assertExact(groups, Set.of("c"));
  }

  @Test
  void umInicioAcimaDoTetoSaiInteiro() {
    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(union(List.of(numbered("a", 301), numbered("b", 301), Set.of("c"))), 2);

    assertThat(sizes(groups).keySet())
        .containsExactlyInAnyOrder(
            PackageGroup.prefix("a"), PackageGroup.prefix("b"), PackageGroup.exact("c"));
  }

  @Test
  void oEmpateDeMembrosEDesfeitoPelaChave() {
    final Set<String> x = numbered("x", 101);
    final Set<String> y = numbered("y", 101);
    final Set<String> z = numbered("z", 101);

    final Map<String, PackageGroup> groups = PrefixRollup.groups(union(List.of(x, y, z)), 300);

    assertThat(sizes(groups)).hasSize(203);
    assertExact(groups, x);
    assertExact(groups, y);
    assertGroup(groups, z, PackageGroup.prefix("z"));
  }

  @Test
  void aChaveDoEmpateEComparadaEmPontoDeCodigo() {
    final String fullwidth = Character.toString(0xFF21);
    final String mathBold = Character.toString(0x1D400);
    final Set<String> world =
        packages(fullwidth + ".p1", fullwidth + ".p2", mathBold + ".p1", mathBold + ".p2", "c");

    final Map<String, PackageGroup> groups = PrefixRollup.groups(world, 4);

    assertExact(groups, List.of(fullwidth + ".p1", fullwidth + ".p2", "c"));
    assertGroup(groups, List.of(mathBold + ".p1", mathBold + ".p2"), PackageGroup.prefix(mathBold));
  }

  @Test
  void umPacoteIgualAoPrefixoComumFicaExatoEOsFilhosViramPrefixos() {
    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(packages("org.x", "org.x.a", "org.x.a.k", "org.x.b", "org.x.b.k"), 3);

    assertExact(groups, List.of("org.x"));
    assertGroup(groups, List.of("org.x.a", "org.x.a.k"), PackageGroup.prefix("org.x.a"));
    assertGroup(groups, List.of("org.x.b", "org.x.b.k"), PackageGroup.prefix("org.x.b"));
  }

  @Test
  void oGrupoDeInicioRecusadoContemOPacoteDeMesmoNome() {
    final Set<String> world = packages("org.x", "org.x.a", "org.x.b");

    final Map<String, PackageGroup> groups = PrefixRollup.groups(world, 2);

    assertGroup(groups, world, PackageGroup.prefix("org.x"));
  }

  @Test
  void oPacotePadraoEUmGrupoExatoAoLadoDosPrefixos() {
    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(packages("", "a.p1", "a.p2", "b.p1", "b.p2"), 3);

    assertThat(groups.get("")).isEqualTo(PackageGroup.exact(""));
    assertGroup(groups, List.of("a.p1", "a.p2"), PackageGroup.prefix("a"));
    assertGroup(groups, List.of("b.p1", "b.p2"), PackageGroup.prefix("b"));
  }

  @Test
  void aNormalizacaoDesceACadeiaDeFilhoUnico() {
    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(packages("x.y.z.p1", "x.y.z.p2", "w"), 2);

    assertGroup(groups, List.of("x.y.z.p1", "x.y.z.p2"), PackageGroup.prefix("x.y.z"));
    assertExact(groups, List.of("w"));
  }

  @Test
  void depoisDeUmaRecusaUmCandidatoMenorAindaEAceito() {
    final Set<String> a = packages("a.p1", "a.p2", "a.p3", "a.p4", "a.p5");
    final Map<String, PackageGroup> groups =
        PrefixRollup.groups(union(List.of(a, packages("b.p1", "b.p2", "c"))), 5);

    assertThat(sizes(groups)).hasSize(4);
    assertGroup(groups, a, PackageGroup.prefix("a"));
    assertExact(groups, List.of("b.p1", "b.p2", "c"));
  }

  @Test
  void umTetoAbaixoDeUmERecusado() {
    final Set<String> world = packages("a", "b");

    assertThatThrownBy(() -> PrefixRollup.groups(world, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PrefixRollup.groups(world, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void semPacotesNaoHaGrupo() {
    assertThat(PrefixRollup.groups(Set.of(), 300)).isEmpty();
  }

  @Test
  void umPacoteSoEOSeuGrupoExato() {
    assertThat(PrefixRollup.groups(Set.of("only.one"), 1))
        .containsOnly(Map.entry("only.one", PackageGroup.exact("only.one")));
  }

  @Test
  void oMapaDevolvidoEImutavel() {
    final Map<String, PackageGroup> groups = PrefixRollup.groups(Set.of("a", "b"), 300);

    assertThatThrownBy(() -> groups.put("c", PackageGroup.exact("c")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static List<String> readLines(String resource) throws IOException {
    try (InputStream in = PrefixRollupTest.class.getResourceAsStream(RESOURCES + resource)) {
      Objects.requireNonNull(in, resource);
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return reader.lines().toList();
      }
    }
  }

  private static String packageName(String packageId) {
    assertThat(packageId).startsWith("pkg:");
    return packageId.substring("pkg:".length());
  }

  private static PackageGroup group(String nodeId) {
    if (nodeId.startsWith("pfx:")) {
      return PackageGroup.prefix(nodeId.substring("pfx:".length()));
    }
    return PackageGroup.exact(packageName(nodeId));
  }

  private static Map<String, PackageGroup> oracle(String corpus, int packageCount)
      throws IOException {
    final List<String> packageIds = readLines(corpus + ".packages.txt");
    final List<String> mapping = readLines(corpus + ".groups.txt");
    assertThat(packageIds).hasSize(packageCount).doesNotHaveDuplicates();
    assertThat(mapping).hasSize(packageCount);

    final Map<String, PackageGroup> expected = new LinkedHashMap<>();
    final List<String> mappedIds = new ArrayList<>();
    for (final String line : mapping) {
      final String[] columns = line.split("\t", -1);
      assertThat(columns).as(line).hasSize(2);
      mappedIds.add(columns[0]);
      expected.put(packageName(columns[0]), group(columns[1]));
    }
    assertThat(mappedIds).isEqualTo(packageIds);
    return expected;
  }

  @Test
  void oElasticsearchComTestesDaOMesmoMapaQueOOraculo() throws IOException {
    final Map<String, PackageGroup> expected = oracle("elasticsearch-with-tests", 2129);
    final Map<PackageGroup, Long> expectedSizes = sizes(expected);
    assertThat(expectedSizes).hasSize(300);
    assertThat(expectedSizes.values()).contains(152L).allMatch(size -> size <= 152L);
    assertThat(expectedSizes.keySet()).filteredOn(group -> !group.isPrefix()).hasSize(132);
    assertThat(expected).containsKey("");

    final Map<String, PackageGroup> actual = PrefixRollup.groups(expected.keySet(), 300);

    assertThat(new HashMap<>(actual)).isEqualTo(new HashMap<>(expected));
  }

  @Test
  void oSpringComTestesDaOMesmoMapaQueOOraculo() throws IOException {
    final Map<String, PackageGroup> expected = oracle("spring-framework-with-tests", 817);
    final Map<PackageGroup, Long> expectedSizes = sizes(expected);
    assertThat(expectedSizes).hasSize(300);
    assertThat(expectedSizes.values()).contains(13L).allMatch(size -> size <= 13L);
    assertThat(expectedSizes.keySet()).filteredOn(group -> !group.isPrefix()).hasSize(168);

    final Map<String, PackageGroup> actual = PrefixRollup.groups(expected.keySet(), 300);

    assertThat(new HashMap<>(actual)).isEqualTo(new HashMap<>(expected));
  }
}
