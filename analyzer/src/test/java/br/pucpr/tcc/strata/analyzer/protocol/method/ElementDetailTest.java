package br.pucpr.tcc.strata.analyzer.protocol.method;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.method.OraculoDoCache.Elemento;
import br.pucpr.tcc.strata.analyzer.protocol.method.OraculoDoCache.Grafo;
import br.pucpr.tcc.strata.analyzer.protocol.method.OraculoDoCache.Metrica;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ElementDetailTest {

  private static final List<String> FIXTURES =
      List.of(
          "broken-source",
          "cyclic",
          "inheritance",
          "long-history",
          "mass-change",
          "merge-commits",
          "method-calls",
          "missing-classpath",
          "multi-module",
          "renamed-files",
          "simple-packages",
          "unusual-names");

  private static final Set<String> COM_DEPENDENCIAS =
      Set.of(
          "broken-source",
          "cyclic",
          "inheritance",
          "method-calls",
          "missing-classpath",
          "multi-module",
          "simple-packages");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final int MAX_PAGINAS = 1_000;

  private static final String CURSOR_DE_OUTRA_LISTA =
      "o cursor não aponta para uma linha desta listagem";

  @TempDir static Path dadosDaClasse;

  private static final Map<String, Object> MUNDOS = new HashMap<>();

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  @AfterAll
  static synchronized void fecharOsMundos() {
    for (final Object mundo : MUNDOS.values()) {
      if (mundo instanceof MundoDeFixture aberto) {
        aberto.close();
      }
    }
    MUNDOS.clear();
  }

  static Stream<String> fixtures() {
    return FIXTURES.stream();
  }

  private static synchronized MundoDeFixture mundo(String fixture) {
    final Object construido =
        MUNDOS.computeIfAbsent(
            fixture,
            nome -> {
              try {
                return MundoDeFixture.analisar(nome, dadosDaClasse);
              } catch (Exception | AssertionError falha) {
                return falha;
              }
            });
    if (construido instanceof Throwable falha) {
      throw new AssertionError("a fixture " + fixture + " não ficou pronta", falha);
    }
    return (MundoDeFixture) construido;
  }

  private record Desenho(
      Map<String, JsonNode> nos, List<JsonNode> arestasDePacote, List<JsonNode> arestasDeTipo) {}

  private record Paginas(List<JsonNode> paginas) {
    List<JsonNode> itens() {
      final List<JsonNode> todos = new ArrayList<>();
      paginas.forEach(p -> p.path("items").forEach(todos::add));
      return List.copyOf(todos);
    }
  }

  private static ObjectNode objeto() {
    return JSON.createObjectNode();
  }

  private static Map<String, JsonNode> enumerar(MundoDeFixture mundo) throws IOException {
    final Map<String, JsonNode> itens = new LinkedHashMap<>();
    for (final JsonNode pacote : outline(mundo, null)) {
      itens.put(pacote.path("id").asText(), pacote);
    }
    for (final String pacote : List.copyOf(itens.keySet())) {
      for (final JsonNode tipo : outline(mundo, pacote)) {
        itens.put(tipo.path("id").asText(), tipo);
      }
    }
    return itens;
  }

  private static List<JsonNode> outline(MundoDeFixture mundo, String pai) throws IOException {
    final List<JsonNode> itens = new ArrayList<>();
    final Set<String> vistos = new HashSet<>();
    String cursor = null;
    for (int pagina = 0; pagina < MAX_PAGINAS; pagina++) {
      final ObjectNode pedido = objeto().put("limit", 2).put("parentId", pai);
      if (cursor != null) {
        pedido.put("cursor", cursor);
      }
      final JsonNode resposta = mundo.ok("outline.children", pedido);
      resposta.path("items").forEach(itens::add);
      if (resposta.path("nextCursor").isNull()) {
        return itens;
      }
      cursor = resposta.path("nextCursor").asText();
      assertThat(vistos.add(cursor)).as("o outline de " + pai + " repete um cursor").isTrue();
    }
    throw new AssertionError("o outline de " + pai + " não terminou");
  }

  private static Map<String, String> rotulos(Map<String, JsonNode> enumeracao) {
    final Map<String, String> rotulos = new HashMap<>();
    enumeracao.forEach((id, item) -> rotulos.put(id, item.path("label").asText()));
    return rotulos;
  }

  private static Desenho desenhar(MundoDeFixture mundo, Map<String, JsonNode> enumeracao)
      throws IOException {
    final Map<String, JsonNode> nos = new HashMap<>();
    final List<JsonNode> arestasDePacote = new ArrayList<>();
    final List<JsonNode> arestasDeTipo = new ArrayList<>();
    final JsonNode nivel1 = mundo.ok("graph.slice", objeto().put("level", "PACKAGE"));
    assertThat(nivel1.path("aggregated").asBoolean()).as("o nível 1 de uma fixture").isFalse();
    assertThat(nivel1.path("truncated").asBoolean()).isFalse();
    nivel1.path("nodes").forEach(no -> nos.put(no.path("id").asText(), no));
    nivel1.path("edges").forEach(arestasDePacote::add);
    for (final Map.Entry<String, JsonNode> item : enumeracao.entrySet()) {
      if ("PACKAGE".equals(item.getValue().path("kind").asText())) {
        final JsonNode fatia =
            mundo.ok("graph.slice", objeto().put("level", "TYPE").put("parentId", item.getKey()));
        assertThat(fatia.path("truncated").asBoolean()).as(item.getKey()).isFalse();
        fatia.path("nodes").forEach(no -> nos.put(no.path("id").asText(), no));
        fatia.path("edges").forEach(arestasDeTipo::add);
      }
    }
    return new Desenho(Map.copyOf(nos), List.copyOf(arestasDePacote), List.copyOf(arestasDeTipo));
  }

  private static JsonNode descrever(MundoDeFixture mundo, String id) throws IOException {
    return mundo.ok("element.describe", objeto().put("elementId", id));
  }

  private static ObjectNode pedidoDePagina(String id, String sentido) {
    return objeto().put("direction", sentido).put("elementId", id);
  }

  private static Paginas paginar(MundoDeFixture mundo, String id, String sentido, Integer limite)
      throws IOException {
    final List<JsonNode> paginas = new ArrayList<>();
    final Set<String> vistos = new HashSet<>();
    String cursor = null;
    for (int pagina = 0; pagina < MAX_PAGINAS; pagina++) {
      final ObjectNode pedido = pedidoDePagina(id, sentido);
      if (limite != null) {
        pedido.put("limit", limite);
      }
      if (cursor != null) {
        pedido.put("cursor", cursor);
      }
      final JsonNode resposta = mundo.ok("element.dependencies", pedido);
      paginas.add(resposta);
      if (resposta.path("nextCursor").isNull()) {
        return new Paginas(List.copyOf(paginas));
      }
      cursor = resposta.path("nextCursor").asText();
      assertThat(vistos.add(cursor))
          .as(id + " " + sentido + ": um cursor que se repete nunca chega ao fim")
          .isTrue();
    }
    throw new AssertionError(id + " " + sentido + " não terminou");
  }

  private static JsonNode evidencia(MundoDeFixture mundo, String origem, String destino)
      throws IOException {
    return mundo.ok(
        "element.dependencyEvidence", objeto().put("source", origem).put("target", destino));
  }

  private static Set<String> textos(JsonNode quadro) {
    final Set<String> textos = new LinkedHashSet<>();
    coletar(quadro, textos);
    return textos;
  }

  private static void coletar(JsonNode quadro, Set<String> textos) {
    if (quadro.isTextual()) {
      textos.add(quadro.textValue());
    } else if (quadro.isObject()) {
      for (final Map.Entry<String, JsonNode> campo : quadro.properties()) {
        textos.add(campo.getKey());
        coletar(campo.getValue(), textos);
      }
    } else if (quadro.isArray()) {
      quadro.forEach(item -> coletar(item, textos));
    }
  }

  private static Set<String> chaves(JsonNode objeto) {
    final Set<String> chaves = new TreeSet<>();
    objeto.fieldNames().forEachRemaining(chaves::add);
    return chaves;
  }

  @Test
  void asDozeSaoTodasAsFixturesDoCorpus() throws IOException {
    try (Stream<Path> filhos = Files.list(TestRepositories.corpusRoot())) {
      assertThat(
              filhos
                  .filter(filho -> Files.isRegularFile(filho.resolve("expected.json")))
                  .map(filho -> filho.getFileName().toString())
                  .sorted()
                  .toList())
          .containsExactlyElementsOf(FIXTURES);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void nenhumMetodoEmFatiaPaginaDoOutlineOuAchadoDaBusca(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    assertThat(oraculo.metodos()).as("anti-vacuidade: há métodos no cache").isNotEmpty();
    final List<JsonNode> quadros = new ArrayList<>();

    final Map<String, JsonNode> enumeracao = enumerar(mundo);
    quadros.addAll(enumeracao.values());
    final Desenho desenho = desenhar(mundo, enumeracao);
    quadros.addAll(desenho.nos().values());
    quadros.addAll(desenho.arestasDePacote());
    quadros.addAll(desenho.arestasDeTipo());
    for (final Map.Entry<String, JsonNode> item : enumeracao.entrySet()) {
      if ("TYPE".equals(item.getValue().path("kind").asText())) {
        assertThat(outline(mundo, item.getKey())).as("um tipo é folha no outline").isEmpty();
      }
    }
    final Set<String> consultas = new TreeSet<>(List.of("a", "e", "i", "o", "r", "t"));
    for (final Elemento metodo : oraculo.metodos()) {
      final String nome = metodo.id().substring(metodo.id().indexOf('#') + 1).split("\\(")[0];
      if (!nome.startsWith("<")) {
        consultas.add(nome);
      }
    }
    for (final String consulta : consultas) {
      quadros.add(mundo.ok("graph.search", objeto().put("query", consulta)));
      final ObjectNode todosOsTipos = objeto().put("query", consulta);
      todosOsTipos.putArray("kinds").add("PACKAGE").add("TYPE").add("METHOD").add("FILE");
      quadros.add(mundo.ok("graph.search", todosOsTipos));
    }

    for (final JsonNode quadro : quadros) {
      assertThat(textos(quadro))
          .as(fixture + ": " + quadro)
          .noneMatch(texto -> texto.startsWith("method:") || "METHOD".equals(texto));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oDetalheDeCadaPacoteETipoEOQueAFatiaDesenha(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Map<String, JsonNode> enumeracao = enumerar(mundo);
    assertThat(enumeracao.keySet())
        .as("o outline enumera todo pacote e tipo interno do cache, e só eles")
        .containsExactlyInAnyOrderElementsOf(
            oraculo.pacotesETiposInternos().stream().map(Elemento::id).toList());
    final Desenho desenho = desenhar(mundo, enumeracao);

    for (final Map.Entry<String, JsonNode> item : enumeracao.entrySet()) {
      final String id = item.getKey();
      final JsonNode no = desenho.nos().get(id);
      assertThat(no).as(id + ": a fatia desenha o nó").isNotNull();
      final JsonNode detalhe = descrever(mundo, id);
      final JsonNode grafo = detalhe.path("graph");

      assertThat(detalhe.path("elementId").asText()).as(id).isEqualTo(id);
      assertThat(detalhe.path("kind").asText())
          .as(id)
          .isEqualTo(item.getValue().path("kind").asText());
      assertThat(detalhe.path("label").asText())
          .as(id + ": o rótulo do outline e da fatia")
          .isEqualTo(item.getValue().path("label").asText())
          .isEqualTo(no.path("label").asText());
      assertThat(grafo.path("fanIn")).as(id + " fanIn").isEqualTo(no.path("fanIn"));
      assertThat(grafo.path("fanOut")).as(id + " fanOut").isEqualTo(no.path("fanOut"));
      assertThat(Double.doubleToRawLongBits(grafo.path("pagerank").doubleValue()))
          .as(id + ": o pagerank do nó, bit a bit")
          .isEqualTo(Double.doubleToRawLongBits(no.path("pagerank").doubleValue()));
      assertThat(grafo.path("pagerank").isNumber()).as(id).isTrue();
      assertThat(grafo.path("resolutionMix"))
          .as(id + " resolutionMix")
          .isEqualTo(no.path("resolutionMix"));
      assertThat(detalhe.path("metrics").path("loc")).as(id + " loc").isEqualTo(no.path("loc"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oCaminhoDeCadaTipoERelativoARaizEEODaUnidade(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Set<String> arquivos = mundo.arquivosJava();
    int tipos = 0;

    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      final JsonNode caminho = descrever(mundo, elemento.id()).path("filePath");
      if ("PACKAGE".equals(elemento.kind())) {
        assertThat(caminho.isNull()).as(elemento.id() + ": pacote sem arquivo").isTrue();
        continue;
      }
      tipos++;
      assertThat(caminho.isTextual()).as(elemento.id() + ": " + caminho).isTrue();
      final String relativo = caminho.textValue();
      assertThat(relativo)
          .as(elemento.id() + ": o source_file da unidade")
          .isEqualTo(elemento.filePath());
      assertThat(arquivos).as(elemento.id() + ": a varredura independente acha").contains(relativo);
      assertThat(Files.readString(mundo.repositorio().resolve(relativo), UTF_8))
          .as(elemento.id() + ": o arquivo declara o tipo")
          .contains(elemento.simpleName());
    }
    assertThat(tipos).as("anti-vacuidade: a fixture tem tipos").isPositive();

    if ("unusual-names".equals(fixture)) {
      final String bidi =
          "src/main/java/br/pucpr/pedidos/Relat"
              + Character.toString(0x00F3)
              + "rio"
              + Character.toString(0x202E)
              + "gpj.java";
      assertThat(descrever(mundo, "type:br.pucpr.pedidos.RelatorioBidi").path("filePath").asText())
          .as("o U+202E chega ao fio como está no disco")
          .isEqualTo(bidi);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void osMetodosDeCadaTipoSaoOsFilhosMethodNaOrdemDoFonte(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    int soma = 0;

    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      final JsonNode detalhe = descrever(mundo, elemento.id());
      final ArrayNode esperados =
          "PACKAGE".equals(elemento.kind())
              ? JSON.createArrayNode()
              : oraculo.metodosEsperados(elemento);
      esperados.forEach(
          m ->
              assertThat(
                      m.path("signature")
                          .textValue()
                          .codePointCount(0, m.path("signature").textValue().length()))
                  .as("a fixture não chega ao teto de 512 pontos de código")
                  .isLessThanOrEqualTo(OraculoDoCache.MAX_LABEL_CODE_POINTS));

      assertThat(detalhe.path("methods")).as(elemento.id() + ": methods").isEqualTo(esperados);
      assertThat(detalhe.path("methodTotal").asInt())
          .as(elemento.id() + ": methodTotal")
          .isEqualTo(esperados.size());
      assertThat(detalhe.path("methodTotal").isInt()).isTrue();
      soma += esperados.size();
    }
    assertThat(soma)
        .as("todo método do cache aparece no describe do seu tipo")
        .isEqualTo(oraculo.metodos().size())
        .isPositive();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oRestoDoDetalheEALinhaDoCache(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();

    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      final String id = elemento.id();
      final JsonNode detalhe = descrever(mundo, id);
      final boolean pacote = "PACKAGE".equals(elemento.kind());
      assertThat(chaves(detalhe))
          .as(id + ": as chaves do detalhe")
          .containsExactly(
              "elementId",
              "filePath",
              "graph",
              "isTest",
              "kind",
              "label",
              "lines",
              "methodTotal",
              "methods",
              "metrics",
              "modifiers",
              "packageId",
              "qualifiedName",
              "typeKind");
      assertThat(chaves(detalhe.path("graph")))
          .containsExactly("cycle", "fanIn", "fanOut", "pagerank", "resolutionMix");
      assertThat(chaves(detalhe.path("metrics")))
          .containsExactly("ccMax", "ccMean", "ccP90", "ccSum", "loc", "unitCount");

      assertThat(detalhe.path("typeKind").isNull()).as(id).isEqualTo(pacote);
      if (!pacote) {
        assertThat(detalhe.path("typeKind").asText()).as(id).isEqualTo(elemento.typeKind());
      }
      assertThat(detalhe.path("qualifiedName").asText()).as(id).isEqualTo(elemento.qualifiedName());
      if (pacote) {
        assertThat(detalhe.path("packageId").isNull()).as(id).isTrue();
      } else {
        assertThat(detalhe.path("packageId").asText())
            .as(id + ": o pacote declarado")
            .isEqualTo(oraculo.idDoPai(elemento));
      }
      assertThat(detalhe.path("lines"))
          .as(id + " lines")
          .isEqualTo(OraculoDoCache.linhas(elemento));
      final ArrayNode modificadores = JSON.createArrayNode();
      if (!elemento.modifiers().isEmpty()) {
        Arrays.stream(elemento.modifiers().split(" ", -1)).forEach(modificadores::add);
      }
      assertThat(detalhe.path("modifiers")).as(id + " modifiers").isEqualTo(modificadores);
      assertThat(detalhe.path("isTest").isBoolean()).as(id).isTrue();
      assertThat(detalhe.path("isTest").asBoolean()).as(id + " isTest").isEqualTo(elemento.test());

      conferirMetricas(id, detalhe.path("metrics"), oraculo.metrica(elemento).orElseThrow());
      final Grafo grafo = oraculo.grafo(elemento).orElseThrow();
      final JsonNode ciclo = detalhe.path("graph").path("cycle");
      if (grafo.sccId() == null) {
        assertThat(ciclo.isNull()).as(id + ": fora de ciclo").isTrue();
      } else {
        assertThat(ciclo)
            .as(id + " cycle")
            .isEqualTo(
                objeto()
                    .put("id", grafo.sccId().intValue())
                    .put("size", grafo.sccSize().intValue()));
      }
    }
  }

  private static void conferirMetricas(String id, JsonNode metricas, Metrica linha) {
    assertThat(linha.unitCount()).as(id + ": nunca nulo em um PACKAGE ou TYPE").isNotNull();
    assertThat(linha.ccSum()).isNotNull();
    assertThat(linha.ccMax()).isNotNull();
    assertThat(metricas.path("loc").asLong()).as(id + " loc").isEqualTo(linha.loc());
    assertThat(metricas.path("unitCount").asLong())
        .as(id + " unitCount")
        .isEqualTo(linha.unitCount());
    assertThat(metricas.path("ccSum").asLong()).as(id + " ccSum").isEqualTo(linha.ccSum());
    assertThat(metricas.path("ccMax").asLong()).as(id + " ccMax").isEqualTo(linha.ccMax());
    if (linha.ccP90() == null) {
      assertThat(metricas.path("ccP90").isNull()).as(id + " ccP90").isTrue();
    } else {
      assertThat(metricas.path("ccP90").asLong()).as(id + " ccP90").isEqualTo(linha.ccP90());
    }
    final BigDecimal media = OraculoDoCache.mediaEsperada(linha.ccSum(), linha.unitCount());
    final JsonNode emitida = metricas.path("ccMean");
    assertThat(emitida.isNumber()).as(id + " ccMean").isTrue();
    assertThat(emitida.decimalValue())
        .as(id + " ccMean, HALF_UP a uma casa")
        .isEqualByComparingTo(media);
    assertThat(emitida.toString())
        .as(id + ": ccMean na grafia do JSON.stringify")
        .isEqualTo(media.stripTrailingZeros().toPlainString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void asDependenciasPaginamAteOFimComTotaisHonestos(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Map<String, JsonNode> enumeracao = enumerar(mundo);
    final Map<String, String> rotulos = rotulos(enumeracao);
    final Desenho desenho = desenhar(mundo, enumeracao);
    int linhas = 0;

    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      final String id = elemento.id();
      final List<ObjectNode> entrada = oraculo.linhasEsperadas(elemento, "IN", rotulos);
      final List<ObjectNode> saida = oraculo.linhasEsperadas(elemento, "OUT", rotulos);
      final long externos = saida.stream().filter(l -> l.path("isExternal").asBoolean()).count();
      final ObjectNode totais =
          objeto().put("in", entrada.size()).put("out", saida.size() - (int) externos);
      if ("PACKAGE".equals(elemento.kind())) {
        totais.putNull("outExternal");
      } else {
        totais.put("outExternal", (int) externos);
      }
      final JsonNode no = desenho.nos().get(id);
      assertThat(totais.path("in")).as(id + ": in = fanIn").isEqualTo(no.path("fanIn"));
      assertThat(totais.path("out")).as(id + ": out = fanOut").isEqualTo(no.path("fanOut"));

      for (final String sentido : List.of("IN", "OUT")) {
        final List<ObjectNode> esperadas = "IN".equals(sentido) ? entrada : saida;
        linhas += esperadas.size();
        final Paginas padrao = paginar(mundo, id, sentido, null);
        assertThat(padrao.paginas()).as(id + " " + sentido + ": uma página só").hasSize(1);
        final JsonNode pagina = padrao.paginas().get(0);
        assertThat(chaves(pagina)).containsExactly("items", "nextCursor", "totals");
        assertThat(pagina.path("totals")).as(id + " " + sentido + " totals").isEqualTo(totais);
        assertThat(padrao.itens()).as(id + " " + sentido).containsExactlyElementsOf(esperadas);
        final JsonNode externosNoTotal = totais.path("outExternal");
        final int soma =
            "IN".equals(sentido)
                ? totais.path("in").asInt()
                : totais.path("out").asInt()
                    + (externosNoTotal.isNull() ? 0 : externosNoTotal.asInt());
        assertThat(padrao.itens())
            .as(id + " " + sentido + ": Σ linhas = in, ou out + outExternal")
            .hasSize(soma);

        for (final int limite : List.of(1, 2)) {
          final Paginas paginadas = paginar(mundo, id, sentido, limite);
          final String contexto = id + " " + sentido + " limit " + limite;
          assertThat(paginadas.itens()).as(contexto).containsExactlyElementsOf(esperadas);
          for (int k = 0; k < paginadas.paginas().size(); k++) {
            final JsonNode parte = paginadas.paginas().get(k);
            assertThat(parte.path("totals")).as(contexto + ": totais iguais").isEqualTo(totais);
            final boolean ultima = k == paginadas.paginas().size() - 1;
            if (!ultima) {
              assertThat(parte.path("items")).as(contexto + ": cheia com cursor").hasSize(limite);
            }
          }
        }
      }
      final JsonNode alem =
          mundo.ok("element.dependencies", pedidoDePagina(id, "OUT").put("limit", 2_001));
      assertThat(alem.path("items")).as(id + ": 2001 é cortado em 2000").hasSize(saida.size());
    }
    assertThat(linhas)
        .as("todo par aparece na saída da origem e, interno, na entrada do destino")
        .isEqualTo(oraculo.linhasDePaginaNoTotal());
    if (COM_DEPENDENCIAS.contains(fixture)) {
      assertThat(linhas).as("anti-vacuidade: a fixture tem dependências").isPositive();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void cadaLinhaEspelhaOOutroSentidoEAArestaDesenhada(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final Map<String, JsonNode> enumeracao = enumerar(mundo);
    final Desenho desenho = desenhar(mundo, enumeracao);
    final Map<String, List<JsonNode>> entradas = new HashMap<>();
    final Map<String, List<JsonNode>> saidas = new HashMap<>();
    for (final String id : enumeracao.keySet()) {
      entradas.put(id, paginar(mundo, id, "IN", 2).itens());
      saidas.put(id, paginar(mundo, id, "OUT", 2).itens());
    }

    int espelhos = 0;
    for (final Map.Entry<String, List<JsonNode>> saida : saidas.entrySet()) {
      for (final JsonNode linha : saida.getValue()) {
        if (linha.path("isExternal").asBoolean()) {
          continue;
        }
        final String outro = linha.path("elementId").asText();
        final JsonNode espelho = linhaPara(entradas.get(outro), saida.getKey());
        assertThat(espelho)
            .as(saida.getKey() + " -> " + outro + " na entrada de " + outro)
            .isNotNull();
        for (final String campo :
            List.of("edgeId", "weight", "types", "resolution", "ambiguous", "isExternal")) {
          assertThat(espelho.path(campo))
              .as(saida.getKey() + " -> " + outro + " " + campo)
              .isEqualTo(linha.path(campo));
        }
        espelhos++;
      }
    }
    final int linhasDeEntrada = entradas.values().stream().mapToInt(List::size).sum();
    assertThat(espelhos)
        .as("toda linha de entrada é o espelho de uma interna de saída")
        .isEqualTo(linhasDeEntrada);

    final List<JsonNode> desenhadas = new ArrayList<>(desenho.arestasDePacote());
    desenhadas.addAll(desenho.arestasDeTipo());
    for (final JsonNode aresta : desenhadas) {
      final String origem = aresta.path("source").asText();
      final String destino = aresta.path("target").asText();
      final JsonNode naSaida = linhaPara(saidas.get(origem), destino);
      final JsonNode naEntrada = linhaPara(entradas.get(destino), origem);
      for (final JsonNode linha : Arrays.asList(naSaida, naEntrada)) {
        assertThat(linha)
            .as(origem + " -> " + destino + ": a aresta desenhada é listada")
            .isNotNull();
        assertThat(linha.path("edgeId")).as(origem + " -> " + destino).isEqualTo(aresta.path("id"));
        assertThat(linha.path("weight"))
            .as(origem + " -> " + destino)
            .isEqualTo(aresta.path("weight"));
        assertThat(linha.path("types"))
            .as(origem + " -> " + destino)
            .isEqualTo(aresta.path("types"));
        assertThat(linha.path("resolution"))
            .as(origem + " -> " + destino)
            .isEqualTo(aresta.path("resolution"));
      }
    }
    final Map<String, String> pacoteDe = new HashMap<>();
    final OraculoDoCache oraculo = mundo.oraculo();
    oraculo.pacotesETiposInternos().stream()
        .filter(e -> "TYPE".equals(e.kind()))
        .forEach(e -> pacoteDe.put(e.id(), oraculo.idDoPai(e)));
    int noMesmoPacote = 0;
    for (final Map.Entry<String, List<JsonNode>> saida : saidas.entrySet()) {
      for (final JsonNode linha : saida.getValue()) {
        final String outro = linha.path("elementId").asText();
        final String pacote = pacoteDe.get(saida.getKey());
        if (pacote != null && pacote.equals(pacoteDe.get(outro))) {
          noMesmoPacote++;
        }
      }
    }
    assertThat(desenho.arestasDeTipo())
        .as("toda linha de tipo dentro de um pacote é uma aresta desenhada, e só elas")
        .hasSize(noMesmoPacote);
  }

  private static JsonNode linhaPara(List<JsonNode> linhas, String outro) {
    if (linhas == null) {
      return null;
    }
    return linhas.stream()
        .filter(l -> outro.equals(l.path("elementId").asText()))
        .findFirst()
        .orElse(null);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void oCursorEPresoAoSentidoEAAncora(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Map<String, String> rotulos = rotulos(enumerar(mundo));
    final List<Elemento> elementos = oraculo.pacotesETiposInternos();
    int esperados = 0;
    int conferidos = 0;

    for (int i = 0; i < elementos.size(); i++) {
      final Elemento elemento = elementos.get(i);
      final String outroElemento = elementos.get((i + 1) % elementos.size()).id();
      for (final String sentido : List.of("IN", "OUT")) {
        if (oraculo.linhasEsperadas(elemento, sentido, rotulos).size() >= 2) {
          esperados++;
        }
        final JsonNode primeira =
            mundo.ok(
                "element.dependencies", pedidoDePagina(elemento.id(), sentido).put("limit", 1));
        if (primeira.path("nextCursor").isNull()) {
          continue;
        }
        final String cursor = primeira.path("nextCursor").asText();
        assertThat(cursor).as("o cursor tem 24 caracteres base64url").matches("[A-Za-z0-9_-]{24}");
        final String outroSentido = "IN".equals(sentido) ? "OUT" : "IN";
        assertThat(
                recusa(
                    mundo,
                    "element.dependencies",
                    pedidoDePagina(elemento.id(), outroSentido).put("cursor", cursor)))
            .as(elemento.id() + ": o cursor de " + sentido + " no " + outroSentido)
            .isEqualTo(CURSOR_DE_OUTRA_LISTA);
        if (!outroElemento.equals(elemento.id())) {
          assertThat(
                  recusa(
                      mundo,
                      "element.dependencies",
                      pedidoDePagina(outroElemento, sentido).put("cursor", cursor)))
              .as(elemento.id() + ": o cursor em " + outroElemento)
              .isEqualTo(CURSOR_DE_OUTRA_LISTA);
        }
        assertThat(
                recusa(
                    mundo, "outline.children", objeto().putNull("parentId").put("cursor", cursor)))
            .as("o cursor das dependências no outline")
            .isEqualTo("o cursor está malformado");
        conferidos++;
      }
    }
    assertThat(conferidos).as("um cursor por lista de duas linhas ou mais").isEqualTo(esperados);
  }

  private static String recusa(MundoDeFixture mundo, String metodo, ObjectNode payload)
      throws IOException {
    final JsonNode resposta = mundo.pedir(metodo, payload);
    assertThat(resposta.path("ok").asBoolean()).as(metodo + " " + resposta).isFalse();
    assertThat(resposta.path("error").path("code").asText())
        .as(resposta.toString())
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(resposta.path("error").path("params")).isEqualTo(objeto().put("method", metodo));
    return resposta.path("error").path("detail").asText();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void aEvidenciaDeCadaParListadoTemCadaTipoEOArquivoDaOrigem(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Map<String, String> rotulos = rotulos(enumerar(mundo));
    final Map<List<String>, JsonNode> pares = new LinkedHashMap<>();
    final Set<List<String>> daEntrada = new HashSet<>();
    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      if (!"TYPE".equals(elemento.kind())) {
        continue;
      }
      for (final JsonNode linha : paginar(mundo, elemento.id(), "OUT", null).itens()) {
        pares.put(List.of(elemento.id(), linha.path("elementId").asText()), linha);
      }
      for (final JsonNode linha : paginar(mundo, elemento.id(), "IN", null).itens()) {
        daEntrada.add(List.of(linha.path("elementId").asText(), elemento.id()));
      }
    }
    assertThat(pares.keySet())
        .as("a entrada não lista par que a saída não liste")
        .containsAll(daEntrada);
    assertThat(pares).as("todo par de tipos sem laço é listado").hasSize(oraculo.paresDeTipos());
    if (COM_DEPENDENCIAS.contains(fixture)) {
      assertThat(pares).as("anti-vacuidade: a fixture tem pares").isNotEmpty();
    }

    for (final Map.Entry<List<String>, JsonNode> par : pares.entrySet()) {
      final Elemento origem = oraculo.elemento(par.getKey().get(0));
      final Elemento destino = oraculo.elemento(par.getKey().get(1));
      final String contexto = origem.id() + " -> " + destino.id();
      assertThat(oraculo.evidenciasGravadas(origem, destino))
          .as(contexto + ": nenhum par das fixtures passa de 50")
          .isBetween(1L, 50L);
      final JsonNode evidencia = evidencia(mundo, origem.id(), destino.id()).path("evidence");

      assertThat(evidencia)
          .as(contexto)
          .isEqualTo(oraculo.evidenciasEsperadas(origem, destino, rotulos));
      final Set<String> tipos = new TreeSet<>();
      evidencia.forEach(item -> tipos.add(item.path("type").asText()));
      assertThat(tipos)
          .as(contexto + ": todo tipo da linha")
          .isEqualTo(chaves(par.getValue().path("types")));
      evidencia.forEach(
          item ->
              assertThat(item.path("filePath").asText())
                  .as(contexto + ": o arquivo da origem")
                  .isEqualTo(origem.filePath()));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void nenhumaStringDeQuadroEAbsolutaNemCarregaARaizTemporaria(String fixture) throws Exception {
    final MundoDeFixture mundo = mundo(fixture);
    final OraculoDoCache oraculo = mundo.oraculo();
    final Map<String, String> rotulos = rotulos(enumerar(mundo));
    final Set<String> caminhos = new TreeSet<>();
    int pares = 0;
    int paresDoOraculo = 0;

    for (final Elemento elemento : oraculo.pacotesETiposInternos()) {
      if ("TYPE".equals(elemento.kind())) {
        paresDoOraculo += oraculo.linhasEsperadas(elemento, "OUT", rotulos).size();
      }
      final JsonNode detalhe = descrever(mundo, elemento.id());
      if (detalhe.path("filePath").isTextual()) {
        caminhos.add(detalhe.path("filePath").textValue());
      }
      for (final String sentido : List.of("IN", "OUT")) {
        final Paginas paginas = paginar(mundo, elemento.id(), sentido, 2);
        if ("OUT".equals(sentido) && "TYPE".equals(elemento.kind())) {
          for (final JsonNode linha : paginas.itens()) {
            final JsonNode evidencia =
                evidencia(mundo, elemento.id(), linha.path("elementId").asText());
            evidencia
                .path("evidence")
                .forEach(item -> caminhos.add(item.path("filePath").asText()));
            pares++;
          }
        }
      }
    }
    final List<JsonNode> eventos = mundo.eventos();
    for (final JsonNode evento : eventos) {
      mundo.varrer(evento, fixture + " " + evento.path("event").asText());
    }

    assertThat(eventos).as("a análise emitiu eventos").isNotEmpty();
    assertThat(mundo.stringsVarridas()).as("a varredura viu strings").isPositive();
    final Set<String> deTipos = new TreeSet<>();
    oraculo.pacotesETiposInternos().stream()
        .filter(e -> "TYPE".equals(e.kind()))
        .forEach(e -> deTipos.add(e.filePath()));
    assertThat(caminhos).as("o filePath de todo tipo passou pela varredura").containsAll(deTipos);
    assertThat(pares).as(fixture + ": a evidência de todo par listado").isEqualTo(paresDoOraculo);
  }

  @Test
  void oProbeDoMissingClasspathListaOAlvoAmbiguoComoExternoNaoResolvido() throws Exception {
    final MundoDeFixture mundo = mundo("missing-classpath");
    final String probe = "type:com.acme.plugin.spi.Probe";
    final String saida =
        """
        {"items":[\
        {"ambiguous":false,"edgeId":"e_9253dcc2a22d564d","elementId":"type:com.thirdparty.Missing",\
        "isExternal":true,"kind":"TYPE","label":"com.thirdparty.Missing","packageId":null,\
        "qualifiedName":"com.thirdparty.Missing","resolution":"UNRESOLVED_EXTERNAL",\
        "types":{"FIELD_TYPE":1,"IMPORTS":1},"weight":4},\
        {"ambiguous":true,"edgeId":"e_ea4a22b19628c677","elementId":"type:Config",\
        "isExternal":true,"kind":"TYPE","label":"Config","packageId":null,\
        "qualifiedName":"Config","resolution":"UNRESOLVED_EXTERNAL",\
        "types":{"FIELD_TYPE":1},"weight":3},\
        {"ambiguous":false,"edgeId":"e_606214349325c73e","elementId":"type:Registry",\
        "isExternal":true,"kind":"TYPE","label":"Registry","packageId":null,\
        "qualifiedName":"Registry","resolution":"UNRESOLVED_EXTERNAL",\
        "types":{"FIELD_TYPE":1},"weight":3},\
        {"ambiguous":false,"edgeId":"e_6f5f2931900a7ae8","elementId":"type:java.lang.String",\
        "isExternal":true,"kind":"TYPE","label":"java.lang.String","packageId":null,\
        "qualifiedName":"java.lang.String","resolution":"RESOLVED",\
        "types":{"FIELD_TYPE":1},"weight":3}],\
        "nextCursor":null,"totals":{"in":0,"out":0,"outExternal":4}}""";
    final String evidencia =
        """
        {"evidence":[{"filePath":"plugin/src/main/java/com/acme/plugin/spi/Probe.java",\
        "fromMethod":null,"line":18,"resolution":"UNRESOLVED_EXTERNAL","toMethod":null,\
        "type":"FIELD_TYPE"}]}""";

    assertThat(mundo.ok("element.dependencies", pedidoDePagina(probe, "OUT")))
        .isEqualTo(JSON.readTree(saida));
    assertThat(evidencia(mundo, probe, "type:Config")).isEqualTo(JSON.readTree(evidencia));
  }

  @Test
  void oParOrderOrderDtoDoCyclicDaEvidenciaDosTresTipos() throws Exception {
    final MundoDeFixture mundo = mundo("cyclic");
    final String order = "type:com.x.orders.Order";
    final String orderDto = "type:com.x.orders.dto.OrderDto";
    final String ida =
        """
        {"evidence":[\
        {"filePath":"src/main/java/com/x/orders/Order.java","fromMethod":null,"line":3,\
        "resolution":"RESOLVED","toMethod":null,"type":"IMPORTS"},\
        {"filePath":"src/main/java/com/x/orders/Order.java","fromMethod":null,"line":11,\
        "resolution":"RESOLVED","toMethod":null,"type":"FIELD_TYPE"},\
        {"filePath":"src/main/java/com/x/orders/Order.java","fromMethod":\
        {"elementId":"method:com.x.orders.Order#<init>(com.x.orders.dto.OrderDto)",\
        "ownerId":"type:com.x.orders.Order","ownerLabel":"Order","signature":"Order(OrderDto)"},\
        "line":13,"resolution":"RESOLVED","toMethod":null,"type":"PARAMETER_TYPE"}]}""";
    final String volta =
        """
        {"evidence":[\
        {"filePath":"src/main/java/com/x/orders/dto/OrderDto.java","fromMethod":null,"line":3,\
        "resolution":"RESOLVED","toMethod":null,"type":"IMPORTS"},\
        {"filePath":"src/main/java/com/x/orders/dto/OrderDto.java","fromMethod":null,"line":7,\
        "resolution":"RESOLVED","toMethod":null,"type":"FIELD_TYPE"},\
        {"filePath":"src/main/java/com/x/orders/dto/OrderDto.java","fromMethod":\
        {"elementId":"method:com.x.orders.dto.OrderDto#attach(com.x.orders.Order)",\
        "ownerId":"type:com.x.orders.dto.OrderDto","ownerLabel":"OrderDto",\
        "signature":"attach(Order)"},\
        "line":12,"resolution":"RESOLVED","toMethod":null,"type":"PARAMETER_TYPE"}]}""";

    assertThat(evidencia(mundo, order, orderDto)).isEqualTo(JSON.readTree(ida));
    assertThat(evidencia(mundo, orderDto, order)).isEqualTo(JSON.readTree(volta));
    final JsonNode doisDeTres =
        mundo.ok(
            "element.dependencyEvidence",
            objeto().put("limit", 2).put("source", order).put("target", orderDto));
    final ArrayNode primeirosDois = JSON.createArrayNode();
    primeirosDois.add(JSON.readTree(ida).path("evidence").get(0));
    primeirosDois.add(JSON.readTree(ida).path("evidence").get(1));
    assertThat(doisDeTres.path("evidence")).isEqualTo(primeirosDois);
  }
}
