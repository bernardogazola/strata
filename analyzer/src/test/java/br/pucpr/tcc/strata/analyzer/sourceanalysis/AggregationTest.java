package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.ANNOTATION_REFERENCE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.EXTENDS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPLEMENTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.MODULE_DEPENDENCY;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.PARAMETER_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.RETURN_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.INFERRED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.UNRESOLVED_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.EvidenceCollector.Occurrence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class AggregationTest {

  private static final String FILE = "src/main/java/p/A.java";

  private static final CodeElementId A = CodeElementId.ofType("p.A");
  private static final CodeElementId B = CodeElementId.ofType("p.B");
  private static final CodeElementId C = CodeElementId.ofType("p.C");

  @ParameterizedTest(name = "{0} pesa {1}")
  @CsvSource({
    "IMPORTS, 1",
    "EXTENDS, 4",
    "IMPLEMENTS, 4",
    "CALLS, 2",
    "FIELD_TYPE, 3",
    "PARAMETER_TYPE, 1",
    "RETURN_TYPE, 2",
    "ANNOTATION_REFERENCE, 1"
  })
  void cadaTipoTemOPesoDaTabela(DependencyType type, int weight) {
    assertThat(DependencyAggregator.weightOf(type)).isEqualTo(weight);
  }

  @Test
  void moduleDependencyNuncaViraArestaDeTipo() {
    assertThatThrownBy(() -> new Dependency(A, B, MODULE_DEPENDENCY, RESOLVED, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DependencyAggregator.weightOf(MODULE_DEPENDENCY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cincoCallsEDoisFieldTypeDaoUmaArestaDePeso16() {
    final Dependency calls = row(A, B, CALLS, 5, RESOLVED);
    final Dependency fields = row(A, B, FIELD_TYPE, 2, RESOLVED);

    final List<DependencyEdge> edges = DependencyAggregator.aggregate(List.of(fields, calls));

    assertThat(edges)
        .extracting(AggregationTest::edge)
        .containsExactly("p.A -> p.B {CALLS=5, FIELD_TYPE=2} d2 t7 RESOLVED w16");
    assertThat(DependencyAggregator.rowWeight(calls)).isEqualTo(10);
    assertThat(DependencyAggregator.rowWeight(fields)).isEqualTo(6);
  }

  @Test
  void asChavesSaoDaOrdemDoEnumNaoDaAlfabetica() {
    final List<DependencyEdge> edges =
        DependencyAggregator.aggregate(
            List.of(
                row(A, B, PARAMETER_TYPE, 1, RESOLVED),
                row(A, B, FIELD_TYPE, 1, RESOLVED),
                row(A, B, IMPORTS, 1, RESOLVED)));

    assertThat(edges).hasSize(1);
    assertThat(List.copyOf(edges.getFirst().types().keySet()))
        .containsExactly(IMPORTS, FIELD_TYPE, PARAMETER_TYPE);
    assertThat(edges)
        .extracting(AggregationTest::edge)
        .containsExactly(
            "p.A -> p.B {IMPORTS=1, FIELD_TYPE=1, PARAMETER_TYPE=1} d3 t3 RESOLVED w5");
  }

  @Test
  void osOitoTiposComOcorrenciasDistintasConferemCadaPeso() {
    final List<DependencyType> kinds =
        List.of(
            IMPORTS,
            EXTENDS,
            IMPLEMENTS,
            CALLS,
            FIELD_TYPE,
            PARAMETER_TYPE,
            RETURN_TYPE,
            ANNOTATION_REFERENCE);
    final List<Dependency> rows = new ArrayList<>();
    for (int i = kinds.size() - 1; i >= 0; i--) {
      rows.add(row(A, B, kinds.get(i), i + 1, RESOLVED));
    }

    final List<DependencyEdge> edges = DependencyAggregator.aggregate(rows);

    assertThat(edges).hasSize(1);
    final DependencyEdge edge = edges.getFirst();
    assertThat(List.copyOf(edge.types().keySet())).containsExactlyElementsOf(kinds);
    assertThat(edge.distinctTypeCount()).isEqualTo(8);
    assertThat(edge.occurrenceTotal()).isEqualTo(36);
    assertThat(edge.weight()).isEqualTo(72);
  }

  @Test
  void umaLinhaInferredTornaAArestaInferredEmQualquerOrdem() {
    final Dependency calls = row(A, B, CALLS, 5, RESOLVED);
    final Dependency field = row(A, B, FIELD_TYPE, 1, INFERRED);

    assertThat(DependencyAggregator.aggregate(List.of(calls, field)))
        .extracting(AggregationTest::edge)
        .containsExactly("p.A -> p.B {CALLS=5, FIELD_TYPE=1} d2 t6 INFERRED w13");
    assertThat(DependencyAggregator.aggregate(List.of(field, calls)))
        .extracting(AggregationTest::edge)
        .containsExactly("p.A -> p.B {CALLS=5, FIELD_TYPE=1} d2 t6 INFERRED w13");
  }

  @Test
  void aArestaTemAMaisFracaDasQualidades() {
    assertThat(
            DependencyAggregator.aggregate(
                List.of(
                    row(A, B, IMPORTS, 1, RESOLVED),
                    row(A, B, CALLS, 1, INFERRED),
                    row(A, B, FIELD_TYPE, 1, UNRESOLVED_EXTERNAL))))
        .extracting(AggregationTest::edge)
        .containsExactly(
            "p.A -> p.B {IMPORTS=1, CALLS=1, FIELD_TYPE=1} d3 t3 UNRESOLVED_EXTERNAL w6");
    assertThat(
            DependencyAggregator.aggregate(
                List.of(row(A, B, CALLS, 1, INFERRED), row(A, B, FIELD_TYPE, 1, INFERRED))))
        .extracting(DependencyEdge::resolution)
        .containsExactly(INFERRED);
    assertThat(DependencyAggregator.aggregate(List.of(row(A, B, CALLS, 1, RESOLVED))))
        .extracting(DependencyEdge::resolution)
        .containsExactly(RESOLVED);
  }

  @Test
  void arestaDoTipoParaEleMesmoFicaNestaCamada() {
    assertThat(
            DependencyAggregator.aggregate(
                List.of(row(A, A, CALLS, 2, RESOLVED), row(A, A, FIELD_TYPE, 1, RESOLVED))))
        .extracting(AggregationTest::edge)
        .containsExactly("p.A -> p.A {CALLS=2, FIELD_TYPE=1} d2 t3 RESOLVED w7");
  }

  @Test
  void alvoExternoAgregaComoQualquerOutro() {
    final CodeElementId list = CodeElementId.ofType("java.util.List");
    final CodeElementId missing = CodeElementId.ofType("com.thirdparty.Missing");

    assertThat(
            DependencyAggregator.aggregate(
                List.of(
                    row(A, list, IMPORTS, 1, RESOLVED),
                    row(A, list, FIELD_TYPE, 1, RESOLVED),
                    row(A, list, PARAMETER_TYPE, 1, RESOLVED),
                    row(A, missing, IMPORTS, 1, UNRESOLVED_EXTERNAL),
                    row(A, missing, FIELD_TYPE, 1, UNRESOLVED_EXTERNAL))))
        .extracting(AggregationTest::edge)
        .containsExactly(
            "p.A -> com.thirdparty.Missing {IMPORTS=1, FIELD_TYPE=1} d2 t2 UNRESOLVED_EXTERNAL w4",
            "p.A -> java.util.List {IMPORTS=1, FIELD_TYPE=1, PARAMETER_TYPE=1} d3 t3 RESOLVED w5");
  }

  @Test
  void totalTiposEPesoSaoTresNumerosDiferentes() {
    final DependencyEdge edge =
        DependencyAggregator.aggregate(
                List.of(
                    row(A, B, RETURN_TYPE, 2, RESOLVED),
                    row(A, B, CALLS, 3, RESOLVED),
                    row(A, B, IMPORTS, 1, RESOLVED)))
            .getFirst();

    assertThat(edge.occurrenceTotal()).isEqualTo(6);
    assertThat(edge.distinctTypeCount()).isEqualTo(3);
    assertThat(edge.weight()).isEqualTo(11);
  }

  @Test
  void aDirecaoFicaEASaidaEPorOrigemEAlvo() {
    assertThat(
            DependencyAggregator.aggregate(
                List.of(
                    row(B, A, CALLS, 1, RESOLVED),
                    row(A, C, FIELD_TYPE, 1, RESOLVED),
                    row(A, B, CALLS, 1, RESOLVED))))
        .extracting(AggregationTest::edge)
        .containsExactly(
            "p.A -> p.B {CALLS=1} d1 t1 RESOLVED w2",
            "p.A -> p.C {FIELD_TYPE=1} d1 t1 RESOLVED w3",
            "p.B -> p.A {CALLS=1} d1 t1 RESOLVED w2");
  }

  @Test
  void duasLinhasParaOMesmoTriploSaoRecusadas() {
    assertThatThrownBy(
            () ->
                DependencyAggregator.aggregate(
                    List.of(row(A, B, CALLS, 1, RESOLVED), row(A, B, CALLS, 2, RESOLVED))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aOrdemEPorPontoDeCodigoNaoPorUtf16() {
    final String bmp = "p.A" + Character.toString(0xFB01);
    final String astral = "p.A" + Character.toString(0x1D400);
    assertThat(bmp.compareTo(astral)).as("o UTF-16 poria o astral primeiro").isPositive();
    final CodeElementId first = CodeElementId.ofType(bmp);
    final CodeElementId second = CodeElementId.ofType(astral);

    final List<DependencyEdge> edges =
        DependencyAggregator.aggregate(
            List.of(row(second, B, CALLS, 1, RESOLVED), row(first, B, CALLS, 1, RESOLVED)));

    assertThat(edges).extracting(DependencyEdge::source).containsExactly(first, second);
  }

  @Test
  void oMethodCallsDaAsQuatroArestasDoGolden() throws Exception {
    final JsonNode golden =
        new ObjectMapper()
            .readTree(TestRepositories.corpus("method-calls").resolve("expected.json").toFile());
    final List<Dependency> rows = new ArrayList<>();
    for (final JsonNode row : golden.get("dependencies")) {
      rows.add(
          new Dependency(
              new CodeElementId(row.get("from").asText()),
              new CodeElementId(row.get("to").asText()),
              DependencyType.valueOf(row.get("type").asText()),
              ResolutionQuality.valueOf(row.get("resolution").asText()),
              row.get("occurrences").asInt()));
    }
    final List<String> expected = new ArrayList<>();
    for (final JsonNode edge : golden.get("edges")) {
      final List<String> types = new ArrayList<>();
      edge.get("types")
          .properties()
          .forEach(type -> types.add(type.getKey() + "=" + type.getValue().asInt()));
      expected.add(
          new CodeElementId(edge.get("from").asText()).sortKey()
              + " -> "
              + new CodeElementId(edge.get("to").asText()).sortKey()
              + " {"
              + String.join(", ", types)
              + "} d"
              + edge.get("distinctTypeCount").asInt()
              + " t"
              + edge.get("occurrenceTotal").asInt()
              + " "
              + edge.get("quality").asText()
              + " w"
              + edge.get("weight").asInt());
    }

    assertThat(expected).as("o golden tem as 4 arestas").hasSize(4);
    assertThat(DependencyAggregator.aggregate(rows))
        .extracting(AggregationTest::edge)
        .containsExactlyElementsOf(expected);
  }

  @Test
  void aLinhaCanonicaTemAQualidadeMaisFracaDasOcorrencias() {
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(call(A, B, RESOLVED, 3, 30, null));
    collector.add(call(A, B, INFERRED, 4, 40, null));
    collector.add(call(A, B, RESOLVED, 5, 50, null));

    final List<ExtractedDependency> rows = collector.collect();

    assertThat(rows)
        .extracting(row -> row.dependency())
        .containsExactly(row(A, B, CALLS, 3, INFERRED));
    assertThat(rows.getFirst().evidence())
        .extracting(DependencyEvidence::line, DependencyEvidence::resolution)
        .containsExactly(tuple(3, RESOLVED), tuple(4, INFERRED), tuple(5, RESOLVED));
    assertThat(rows.getFirst().filePath()).isEqualTo(FILE);
  }

  @Test
  void asContagensSaoPorOcorrenciaEPorOrigem() {
    final CodeElementId external = CodeElementId.ofType("Config");
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(call(A, B, RESOLVED, 3, 30, null));
    collector.add(call(A, B, INFERRED, 4, 40, null));
    collector.add(call(A, B, RESOLVED, 5, 50, null));
    collector.add(occurrence(A, external, FIELD_TYPE, RESOLVED, false, 6, 60, null));
    collector.add(
        new Occurrence(
            A, external, FIELD_TYPE, UNRESOLVED_EXTERNAL, false, true, 7, 70, null, null));
    collector.add(call(C, B, INFERRED, 8, 80, null));

    assertThat(collector.counts())
        .containsOnly(
            Map.entry(A, new ResolutionCounts(3, 1, 1, 1)),
            Map.entry(C, new ResolutionCounts(0, 1, 0, 0)));
  }

  @Test
  void aLinhaAmbiguaEOOuDasOcorrenciasEOToMethodVaiParaAEvidencia() {
    final CodeElementId external = CodeElementId.ofType("Config");
    final CodeElementId callee = new CodeElementId("method:p.B#go()");
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(
        new Occurrence(
            A, external, FIELD_TYPE, UNRESOLVED_EXTERNAL, false, true, 3, 30, null, null));
    collector.add(
        new Occurrence(
            A, external, FIELD_TYPE, UNRESOLVED_EXTERNAL, false, false, 4, 40, null, null));
    collector.add(new Occurrence(A, B, CALLS, RESOLVED, true, false, 5, 50, null, callee));

    final List<ExtractedDependency> rows = collector.collect();

    assertThat(rows)
        .extracting(row -> row.dependency().target(), ExtractedDependency::ambiguous)
        .containsExactly(tuple(external, true), tuple(B, false));
    assertThat(rows.getLast().evidence())
        .extracting(DependencyEvidence::toMethod)
        .containsExactly(callee);
  }

  @Test
  void oTetoGuardaAsVintePrimeirasLinhasEContaTodas() {
    assertThat(AnalysisConstants.DEPENDENCY_EVIDENCE_CAP).isEqualTo(20);
    final ExtractedDependency twentyFive = single(shuffledCalls(25));
    assertThat(twentyFive.dependency().occurrences()).isEqualTo(25);
    assertThat(twentyFive.evidence())
        .extracting(DependencyEvidence::line)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());

    final ExtractedDependency twenty = single(shuffledCalls(20));
    assertThat(twenty.dependency().occurrences()).isEqualTo(20);
    assertThat(twenty.evidence()).hasSize(20);

    final ExtractedDependency twentyOne = single(shuffledCalls(21));
    assertThat(twentyOne.dependency().occurrences()).isEqualTo(21);
    assertThat(twentyOne.evidence())
        .extracting(DependencyEvidence::line)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
  }

  @Test
  void oTetoEPorLinhaCanonicaNaoPorAresta() {
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    for (int line = 1; line <= 25; line++) {
      collector.add(call(A, B, RESOLVED, line, line * 10, null));
    }
    for (int line = 30; line <= 32; line++) {
      collector.add(occurrence(A, B, FIELD_TYPE, RESOLVED, true, line, line * 10, null));
    }

    final List<ExtractedDependency> rows = collector.collect();

    assertThat(rows).extracting(row -> row.evidence().size()).containsExactly(20, 3);
    assertThat(
            DependencyAggregator.aggregate(
                rows.stream().map(ExtractedDependency::dependency).toList()))
        .extracting(AggregationTest::edge)
        .containsExactly("p.A -> p.B {CALLS=25, FIELD_TYPE=3} d2 t28 RESOLVED w59");
  }

  @Test
  void aOrdemDaEvidenciaELinhaDepoisMetodoDepoisDeslocamento() {
    final CodeElementId methodA = CodeElementId.ofMethod("p.A", "a", List.of());
    final CodeElementId methodB = CodeElementId.ofMethod("p.A", "b", List.of());
    final CodeElementId methodZ = CodeElementId.ofMethod("p.A", "z", List.of());
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(call(A, B, RESOLVED, 0, -1, null));
    collector.add(call(A, B, RESOLVED, 5, 50, methodB));
    collector.add(call(A, B, INFERRED, 5, 90, methodA));
    collector.add(call(A, B, RESOLVED, 5, 70, null));
    collector.add(call(A, B, RESOLVED, 3, 200, methodZ));
    collector.add(call(A, B, RESOLVED, 5, 40, methodA));

    final ExtractedDependency row = single(collector);

    assertThat(row.evidence())
        .extracting(AggregationTest::evidence)
        .containsExactly(
            "L3 method:p.A#z() RESOLVED",
            "L5 - RESOLVED",
            "L5 method:p.A#a() RESOLVED",
            "L5 method:p.A#a() INFERRED",
            "L5 method:p.A#b() RESOLVED",
            "L0 - RESOLVED");
  }

  @Test
  void aLinhaDesconhecidaEAPrimeiraASairNoTeto() {
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(call(A, B, RESOLVED, 0, -1, null));
    for (int line = 20; line >= 1; line--) {
      collector.add(call(A, B, RESOLVED, line, line * 10, null));
    }
    collector.add(call(A, B, RESOLVED, 0, -1, null));

    final ExtractedDependency row = single(collector);

    assertThat(row.dependency().occurrences()).isEqualTo(22);
    assertThat(row.evidence())
        .extracting(DependencyEvidence::line)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
  }

  @Test
  void asLinhasSaemEmOrdemDeOrigemAlvoETipo() {
    final CodeElementId list = CodeElementId.ofType("java.util.List");
    final CodeElementId bmp = CodeElementId.ofType("p.A" + Character.toString(0xFB01));
    final CodeElementId astral = CodeElementId.ofType("p.A" + Character.toString(0x1D400));
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    collector.add(call(B, A, RESOLVED, 9, 90, null));
    collector.add(occurrence(astral, B, CALLS, RESOLVED, true, 8, 80, null));
    collector.add(occurrence(A, list, FIELD_TYPE, RESOLVED, false, 7, 70, null));
    collector.add(occurrence(A, B, RETURN_TYPE, RESOLVED, true, 6, 60, null));
    collector.add(occurrence(bmp, B, CALLS, RESOLVED, true, 5, 50, null));
    collector.add(occurrence(A, B, IMPORTS, RESOLVED, true, 4, 40, null));
    collector.add(call(A, B, RESOLVED, 3, 30, null));

    final List<ExtractedDependency> rows = collector.collect();

    assertThat(rows)
        .extracting(RecordingStructureSink::tuple)
        .containsExactly(
            "p.A -> java.util.List FIELD_TYPE x1 RESOLVED external",
            "p.A -> p.B IMPORTS x1 RESOLVED",
            "p.A -> p.B CALLS x1 RESOLVED",
            "p.A -> p.B RETURN_TYPE x1 RESOLVED",
            "p.A" + Character.toString(0xFB01) + " -> p.B CALLS x1 RESOLVED",
            "p.A" + Character.toString(0x1D400) + " -> p.B CALLS x1 RESOLVED",
            "p.B -> p.A CALLS x1 RESOLVED");
    assertThat(rows).extracting(ExtractedDependency::filePath).containsOnly(FILE);
  }

  @ParameterizedTest(name = "weakest({0}, {1}) = {2}")
  @CsvSource({
    "RESOLVED, RESOLVED, RESOLVED",
    "RESOLVED, INFERRED, INFERRED",
    "RESOLVED, UNRESOLVED_EXTERNAL, UNRESOLVED_EXTERNAL",
    "INFERRED, RESOLVED, INFERRED",
    "INFERRED, INFERRED, INFERRED",
    "INFERRED, UNRESOLVED_EXTERNAL, UNRESOLVED_EXTERNAL",
    "UNRESOLVED_EXTERNAL, RESOLVED, UNRESOLVED_EXTERNAL",
    "UNRESOLVED_EXTERNAL, INFERRED, UNRESOLVED_EXTERNAL",
    "UNRESOLVED_EXTERNAL, UNRESOLVED_EXTERNAL, UNRESOLVED_EXTERNAL"
  })
  void aMaisFracaDeDuasQualidadesNasDuasOrdens(
      ResolutionQuality first, ResolutionQuality second, ResolutionQuality weakest) {
    assertThat(ResolutionQuality.weakest(first, second)).isEqualTo(weakest);
    assertThat(ResolutionQuality.weakest(second, first)).isEqualTo(weakest);
  }

  private static Dependency row(
      CodeElementId source,
      CodeElementId target,
      DependencyType type,
      int occurrences,
      ResolutionQuality resolution) {
    return new Dependency(source, target, type, resolution, occurrences);
  }

  private static Occurrence occurrence(
      CodeElementId source,
      CodeElementId target,
      DependencyType type,
      ResolutionQuality resolution,
      boolean targetDeclared,
      int line,
      int offset,
      CodeElementId fromMethod) {
    return new Occurrence(
        source, target, type, resolution, targetDeclared, false, line, offset, fromMethod, null);
  }

  private static Occurrence call(
      CodeElementId source,
      CodeElementId target,
      ResolutionQuality resolution,
      int line,
      int offset,
      CodeElementId fromMethod) {
    return occurrence(source, target, CALLS, resolution, true, line, offset, fromMethod);
  }

  private static EvidenceCollector shuffledCalls(int count) {
    final EvidenceCollector collector = new EvidenceCollector(FILE);
    for (int i = 0; i < count; i++) {
      final int line = Math.floorMod(i * 11, count) + 1;
      collector.add(call(A, B, RESOLVED, line, line * 10, null));
    }
    return collector;
  }

  private static ExtractedDependency single(EvidenceCollector collector) {
    final List<ExtractedDependency> rows = collector.collect();
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  private static String edge(DependencyEdge edge) {
    final Map<DependencyType, Integer> types = edge.types();
    return edge.source().sortKey()
        + " -> "
        + edge.target().sortKey()
        + " "
        + types
        + " d"
        + edge.distinctTypeCount()
        + " t"
        + edge.occurrenceTotal()
        + " "
        + edge.resolution()
        + " w"
        + edge.weight();
  }

  private static String evidence(DependencyEvidence evidence) {
    return "L"
        + evidence.line()
        + " "
        + (evidence.fromMethod() == null ? "-" : evidence.fromMethod().value())
        + " "
        + evidence.resolution();
  }
}
