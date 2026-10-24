package br.pucpr.tcc.strata.analyzer.protocol.method;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.protocol.CanonicalJson;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.JsonLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.structure.DependencyCursor;
import br.pucpr.tcc.strata.analyzer.structure.DependencyDirection;
import br.pucpr.tcc.strata.analyzer.structure.DependencyEntry;
import br.pucpr.tcc.strata.analyzer.structure.DependencyPage;
import br.pucpr.tcc.strata.analyzer.structure.DependencyPageBytes;
import br.pucpr.tcc.strata.analyzer.structure.DependencyPageQuery;
import br.pucpr.tcc.strata.analyzer.structure.DependencyTotals;
import br.pucpr.tcc.strata.analyzer.structure.EdgeIds;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.NodeValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DependencyPageFrameTest {

  private static final CanonicalJson CANONICAL = new CanonicalJson();

  private static final String Z_MATEMATICO = Character.toString(0x1D419);

  private static final String CONTROLE = Character.toString(0x01);

  private static final String PACOTE_LONGO = "p".repeat(3_990);

  @TempDir private Path temp;

  private StructureCaches cache;

  @BeforeEach
  void abrir() throws Exception {
    cache = StructureCaches.create(temp);
  }

  @AfterEach
  void fechar() throws Exception {
    cache.close();
  }

  private static DependencyEntry linha(
      String id,
      String rotulo,
      String nome,
      String pacote,
      boolean externa,
      boolean ambigua,
      ResolutionQuality qualidade,
      Map<DependencyType, Long> tipos,
      long peso) {
    final CodeElementId elemento = new CodeElementId(id);
    return new DependencyEntry(
        EdgeIds.of("type:app.Alvo", id),
        elemento,
        elemento.kind(),
        rotulo,
        nome,
        pacote == null ? null : new CodeElementId(pacote),
        externa,
        ambigua,
        qualidade,
        tipos,
        peso);
  }

  private static Map<DependencyType, Long> todosOsTiposNoMaximo() {
    final Map<DependencyType, Long> tipos = new EnumMap<>(DependencyType.class);
    for (final DependencyType tipo : DependencyType.values()) {
      if (tipo.isTypeEdge()) {
        tipos.put(tipo, Long.MAX_VALUE);
      }
    }
    return tipos;
  }

  private static String ateBytes(String prefixo, int bytes) {
    return prefixo + "x".repeat(bytes - prefixo.getBytes(UTF_8).length);
  }

  private static List<DependencyEntry> adversarias() {
    final String idNoTeto = ateBytes("type:q.", AnalysisConstants.MAX_ELEMENT_ID_BYTES);
    final String pacoteNoTeto = ateBytes("pkg:q", AnalysisConstants.MAX_ELEMENT_ID_BYTES);
    final String rotuloForaDoBmp = Z_MATEMATICO.repeat(AnalysisConstants.MAX_LABEL_CHARS);
    return List.of(
        linha(
            "type:a.B",
            "B",
            "a.B",
            "pkg:a",
            false,
            false,
            ResolutionQuality.RESOLVED,
            Map.of(DependencyType.IMPORTS, 1L),
            1),
        linha(
            "type:Foo",
            "Foo",
            "Foo",
            null,
            true,
            true,
            ResolutionQuality.UNRESOLVED_EXTERNAL,
            todosOsTiposNoMaximo(),
            Long.MAX_VALUE),
        linha(
            idNoTeto,
            rotuloForaDoBmp,
            idNoTeto.substring("type:".length()),
            pacoteNoTeto,
            false,
            false,
            ResolutionQuality.INFERRED,
            todosOsTiposNoMaximo(),
            Long.MAX_VALUE),
        linha(
            "type:" + CONTROLE.repeat(4_000),
            CONTROLE.repeat(AnalysisConstants.MAX_LABEL_CHARS),
            "\"\\".repeat(2_048),
            "pkg:" + "\"".repeat(4_000),
            false,
            false,
            ResolutionQuality.RESOLVED,
            Map.of(DependencyType.CALLS, 3L),
            6),
        linha(
            "pkg:" + Z_MATEMATICO.repeat(1_000),
            "x",
            "y",
            null,
            false,
            false,
            ResolutionQuality.RESOLVED,
            Map.of(DependencyType.FIELD_TYPE, 9L),
            1));
  }

  @Test
  void aEstimativaCobreOsBytesCanonicosDeCadaLinhaEDaPagina() {
    final List<DependencyEntry> linhas = adversarias();
    long estimado = DependencyPageBytes.PAGE_OVERHEAD_BYTES;

    for (final DependencyEntry entrada : linhas) {
      final DependencyPageView pagina =
          DependencyPageView.of(new DependencyPage(List.of(entrada), null, totaisMaximos()));
      final int bytes = CANONICAL.valueToBytes(pagina.items().getFirst()).length;
      assertThat(DependencyPageBytes.estimate(entrada))
          .as("a linha %s", entrada.elementId().value().substring(0, 8))
          .isGreaterThanOrEqualTo(bytes + 1L);
      estimado += DependencyPageBytes.estimate(entrada);
    }
    final DependencyPage pagina =
        new DependencyPage(
            linhas,
            new DependencyCursor(DependencyDirection.OUT, Long.MAX_VALUE, Long.MAX_VALUE),
            totaisMaximos());
    assertThat(estimado)
        .isGreaterThanOrEqualTo(CANONICAL.valueToBytes(DependencyPageView.of(pagina)).length);
  }

  private static DependencyTotals totaisMaximos() {
    return new DependencyTotals(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  @Test
  void duasMilLinhasNoTetoLegalVaoAteOFimComCadaPaginaDentroDoOrcamento() throws Exception {
    final long analise = mundoDoTetoLegal();
    final List<String> esperadas = new ArrayList<>();
    for (int i = 0; i < 2_000; i++) {
      esperadas.add(idNoTeto(i));
    }
    final CodeElementId centro = new CodeElementId("type:app.Centro");
    final int limite = DependencyPageQuery.pageSize(2_000);
    final List<String> recebidas = new ArrayList<>();
    final List<Integer> tamanhos = new ArrayList<>();
    DependencyCursor cursor = null;

    do {
      final DependencyCursor depois = cursor;
      final DependencyPage pagina =
          cache.read(
              c ->
                  DependencyPageQuery.page(
                      c, analise, centro, DependencyDirection.IN, depois, limite));
      final DependencyPageView vista = DependencyPageView.of(pagina);
      assertThat(CANONICAL.valueToBytes(vista).length)
          .as("o payload da página %d", tamanhos.size())
          .isLessThanOrEqualTo(AnalysisConstants.DEPENDENCY_PAGE_MAX_BYTES);
      assertThat(escritoSemPassarDoQuadro(vista))
          .isLessThanOrEqualTo(AnalysisConstants.SOFT_RESPONSE_BYTES);
      assertThat(pagina.totals()).isEqualTo(new DependencyTotals(2_000, 0, 0));
      pagina.items().forEach(entrada -> recebidas.add(entrada.elementId().value()));
      tamanhos.add(pagina.items().size());
      cursor = pagina.next();
    } while (cursor != null && tamanhos.size() < 2_000);

    assertThat(recebidas).isEqualTo(esperadas);
    assertThat(tamanhos).hasSizeGreaterThan(1);
    assertThat(tamanhos.subList(0, tamanhos.size() - 1))
        .allSatisfy(tamanho -> assertThat(tamanho).isBetween(1, limite - 1));
  }

  private static int escritoSemPassarDoQuadro(DependencyPageView vista) {
    final ByteArrayOutputStream saida = new ByteArrayOutputStream();
    final JsonLines.Writer escritor =
        new JsonLines.Writer(saida, CANONICAL, AnalysisConstants.MAX_FRAME_BYTES);
    assertThatCode(() -> escritor.emit(Envelope.ok("req_01JQ8ZHB2C3D4E5F6G7H8J9K0", vista)))
        .doesNotThrowAnyException();
    return saida.size() - 1;
  }

  private static String idNoTeto(int i) {
    final String nome =
        ateBytes("type:" + PACOTE_LONGO + ".T", AnalysisConstants.MAX_ELEMENT_ID_BYTES - 4);
    return nome + "%04d".formatted(i);
  }

  private long mundoDoTetoLegal() throws Exception {
    final SyntheticAnalysis a = cache.analysis("an_teto_legal");
    final long pacoteLongo = a.addPackage(PACOTE_LONGO, NodeValues.PLAIN);
    final long app = a.addPackage("app", NodeValues.PLAIN);
    final long centro =
        a.addType(app, "Centro", new NodeValues(1, 2_000, 0, 0.5, ResolutionMix.NONE));
    for (int i = 1_999; i >= 0; i--) {
      final String id = idNoTeto(i);
      assertThat(id.getBytes(UTF_8)).hasSize(AnalysisConstants.MAX_ELEMENT_ID_BYTES);
      final String nome = id.substring(("type:" + PACOTE_LONGO + ".").length());
      final long dependente = a.addType(pacoteLongo, nome, NodeValues.PLAIN);
      a.addDependency(
          dependente, centro, DependencyType.CALLS, ResolutionQuality.RESOLVED, 1, false);
    }
    return a.write().pk();
  }
}
