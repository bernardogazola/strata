package br.pucpr.tcc.strata.analyzer.protocol.method;

import static java.nio.charset.StandardCharsets.UTF_8;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class OraculoDoCache {

  static final int MAX_LABEL_CODE_POINTS = 512;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final long FNV_BASE = 0xcbf29ce484222325L;
  private static final long FNV_PRIMO = 0x100000001b3L;

  private static final List<String> FORCA = List.of("RESOLVED", "INFERRED", "UNRESOLVED_EXTERNAL");

  private static final Map<String, String> COLUNAS_DE_PACOTE = colunasDePacote();

  private final Map<Long, Elemento> porPk;
  private final Map<String, Elemento> porId;
  private final Map<Long, Metrica> metricas;
  private final Map<Long, Grafo> grafos;
  private final List<Dependencia> dependencias;
  private final List<Evidencia> evidencias;
  private final List<ArestaDePacote> arestasDePacote;

  record Elemento(
      long pk,
      String id,
      String kind,
      String simpleName,
      String qualifiedName,
      String typeKind,
      String modifiers,
      boolean external,
      boolean test,
      Integer startLine,
      Integer endLine,
      String filePath,
      Long parentPk) {

    boolean interno() {
      return !external;
    }
  }

  record Metrica(long loc, Long unitCount, Long ccSum, Long ccMax, Long ccP90) {}

  record Grafo(Integer sccId, Integer sccSize, int fanIn, int fanOut) {}

  record Dependencia(
      long pk,
      long sourcePk,
      long targetPk,
      String type,
      String resolution,
      int occurrences,
      boolean ambiguous) {}

  record Evidencia(
      long dependencyPk,
      Long fromMethodPk,
      Long toMethodPk,
      String filePath,
      Integer line,
      String resolution) {}

  record ArestaDePacote(
      long sourcePk,
      long targetPk,
      int typePairCount,
      String resolution,
      Map<String, Integer> types) {}

  private OraculoDoCache(
      List<Elemento> elementos,
      Map<Long, Metrica> metricas,
      Map<Long, Grafo> grafos,
      List<Dependencia> dependencias,
      List<Evidencia> evidencias,
      List<ArestaDePacote> arestasDePacote) {
    final Map<Long, Elemento> pks = new HashMap<>();
    final Map<String, Elemento> ids = new HashMap<>();
    for (final Elemento elemento : elementos) {
      pks.put(elemento.pk(), elemento);
      ids.put(elemento.id(), elemento);
    }
    this.porPk = Map.copyOf(pks);
    this.porId = Map.copyOf(ids);
    this.metricas = Map.copyOf(metricas);
    this.grafos = Map.copyOf(grafos);
    this.dependencias = List.copyOf(dependencias);
    this.evidencias = List.copyOf(evidencias);
    this.arestasDePacote = List.copyOf(arestasDePacote);
  }

  static OraculoDoCache ler(Connection conexao, long analise) throws SQLException {
    return new OraculoDoCache(
        elementos(conexao, analise),
        metricas(conexao, analise),
        grafos(conexao, analise),
        dependencias(conexao, analise),
        evidencias(conexao, analise),
        arestasDePacote(conexao, analise));
  }

  private static List<Elemento> elementos(Connection conexao, long analise) throws SQLException {
    final String sql =
        """
        SELECT e.id, e.element_id, e.kind, e.simple_name, e.qualified_name, e.type_kind,
               e.modifiers, e.is_external, e.is_test, e.start_line, e.end_line,
               f.relative_path, e.parent_pk
          FROM code_element e
          LEFT JOIN source_file f ON f.id = e.file_pk
         WHERE e.analysis_pk = ?""";
    final List<Elemento> linhas = new ArrayList<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          linhas.add(
              new Elemento(
                  linha.getLong(1),
                  linha.getString(2),
                  linha.getString(3),
                  linha.getString(4),
                  linha.getString(5),
                  linha.getString(6),
                  linha.getString(7),
                  linha.getInt(8) == 1,
                  linha.getInt(9) == 1,
                  inteiro(linha, 10),
                  inteiro(linha, 11),
                  linha.getString(12),
                  longo(linha, 13)));
        }
      }
    }
    return linhas;
  }

  private static Map<Long, Metrica> metricas(Connection conexao, long analise) throws SQLException {
    final String sql =
        "SELECT element_pk, loc, unit_count, cc_sum, cc_max, cc_p90 FROM code_metric"
            + " WHERE analysis_pk = ?";
    final Map<Long, Metrica> linhas = new HashMap<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          linhas.put(
              linha.getLong(1),
              new Metrica(
                  linha.getLong(2),
                  longo(linha, 3),
                  longo(linha, 4),
                  longo(linha, 5),
                  longo(linha, 6)));
        }
      }
    }
    return linhas;
  }

  private static Map<Long, Grafo> grafos(Connection conexao, long analise) throws SQLException {
    final String sql =
        "SELECT element_pk, scc_id, scc_size, fan_in, fan_out FROM graph_metric"
            + " WHERE analysis_pk = ?";
    final Map<Long, Grafo> linhas = new HashMap<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          linhas.put(
              linha.getLong(1),
              new Grafo(inteiro(linha, 2), inteiro(linha, 3), linha.getInt(4), linha.getInt(5)));
        }
      }
    }
    return linhas;
  }

  private static List<Dependencia> dependencias(Connection conexao, long analise)
      throws SQLException {
    final String sql =
        "SELECT id, source_pk, target_pk, type, resolution, occurrences, ambiguous"
            + " FROM dependency WHERE analysis_pk = ?";
    final List<Dependencia> linhas = new ArrayList<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          linhas.add(
              new Dependencia(
                  linha.getLong(1),
                  linha.getLong(2),
                  linha.getLong(3),
                  linha.getString(4),
                  linha.getString(5),
                  linha.getInt(6),
                  linha.getInt(7) == 1));
        }
      }
    }
    return linhas;
  }

  private static List<Evidencia> evidencias(Connection conexao, long analise) throws SQLException {
    final String sql =
        """
        SELECT v.dependency_pk, v.from_method_pk, v.to_method_pk, f.relative_path, v.line,
               v.resolution
          FROM dependency_evidence v
          JOIN dependency d ON d.id = v.dependency_pk
          LEFT JOIN source_file f ON f.id = v.file_pk
         WHERE d.analysis_pk = ?""";
    final List<Evidencia> linhas = new ArrayList<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          linhas.add(
              new Evidencia(
                  linha.getLong(1),
                  longo(linha, 2),
                  longo(linha, 3),
                  linha.getString(4),
                  inteiro(linha, 5),
                  linha.getString(6)));
        }
      }
    }
    return linhas;
  }

  private static List<ArestaDePacote> arestasDePacote(Connection conexao, long analise)
      throws SQLException {
    final String sql =
        "SELECT source_pk, target_pk, type_pair_count, resolution, "
            + String.join(", ", COLUNAS_DE_PACOTE.keySet())
            + " FROM package_edge WHERE analysis_pk = ?";
    final List<ArestaDePacote> linhas = new ArrayList<>();
    try (PreparedStatement consulta = conexao.prepareStatement(sql)) {
      consulta.setLong(1, analise);
      try (ResultSet linha = consulta.executeQuery()) {
        while (linha.next()) {
          final Map<String, Integer> tipos = new TreeMap<>();
          int coluna = 5;
          for (final String tipo : COLUNAS_DE_PACOTE.values()) {
            final int ocorrencias = linha.getInt(coluna++);
            if (ocorrencias > 0) {
              tipos.put(tipo, ocorrencias);
            }
          }
          linhas.add(
              new ArestaDePacote(
                  linha.getLong(1),
                  linha.getLong(2),
                  linha.getInt(3),
                  linha.getString(4),
                  Map.copyOf(tipos)));
        }
      }
    }
    return linhas;
  }

  private static Map<String, String> colunasDePacote() {
    final Map<String, String> colunas = new LinkedHashMap<>();
    for (final DependencyType tipo : DependencyType.values()) {
      if (tipo != DependencyType.MODULE_DEPENDENCY) {
        colunas.put(tipo.name().toLowerCase(Locale.ROOT), tipo.name());
      }
    }
    return colunas;
  }

  private static Integer inteiro(ResultSet linha, int coluna) throws SQLException {
    final int valor = linha.getInt(coluna);
    return linha.wasNull() ? null : valor;
  }

  private static Long longo(ResultSet linha, int coluna) throws SQLException {
    final long valor = linha.getLong(coluna);
    return linha.wasNull() ? null : valor;
  }

  List<Elemento> pacotesETiposInternos() {
    return porPk.values().stream()
        .filter(e -> e.interno() && ("PACKAGE".equals(e.kind()) || "TYPE".equals(e.kind())))
        .sorted(Comparator.comparing(Elemento::id, OraculoDoCache::porPontoDeCodigo))
        .toList();
  }

  List<Elemento> metodos() {
    return porPk.values().stream().filter(e -> "METHOD".equals(e.kind())).toList();
  }

  Elemento elemento(String id) {
    return Optional.ofNullable(porId.get(id))
        .orElseThrow(() -> new AssertionError("o cache não tem " + id));
  }

  Elemento elemento(long pk) {
    return Optional.ofNullable(porPk.get(pk))
        .orElseThrow(() -> new AssertionError("o cache não tem a chave " + pk));
  }

  String idDoPai(Elemento elemento) {
    return elemento.parentPk() == null ? null : elemento(elemento.parentPk()).id();
  }

  Optional<Metrica> metrica(Elemento elemento) {
    return Optional.ofNullable(metricas.get(elemento.pk()));
  }

  Optional<Grafo> grafo(Elemento elemento) {
    return Optional.ofNullable(grafos.get(elemento.pk()));
  }

  List<Dependencia> linhasDoPar(Elemento origem, Elemento destino) {
    return dependencias.stream()
        .filter(d -> d.sourcePk() == origem.pk() && d.targetPk() == destino.pk())
        .toList();
  }

  List<ObjectNode> linhasEsperadas(Elemento elemento, String sentido, Map<String, String> rotulos) {
    final boolean saida = "OUT".equals(sentido);
    final List<ObjectNode> linhas = new ArrayList<>();
    if ("PACKAGE".equals(elemento.kind())) {
      for (final ArestaDePacote aresta : arestasDePacote) {
        final long deste = saida ? aresta.sourcePk() : aresta.targetPk();
        if (deste == elemento.pk()) {
          final Elemento outro = elemento(saida ? aresta.targetPk() : aresta.sourcePk());
          linhas.add(
              linha(
                  elemento,
                  outro,
                  saida,
                  rotulos,
                  aresta.types(),
                  aresta.typePairCount(),
                  aresta.resolution(),
                  false));
        }
      }
    } else {
      final Map<Long, List<Dependencia>> porOutro = new TreeMap<>();
      for (final Dependencia d : dependencias) {
        final long deste = saida ? d.sourcePk() : d.targetPk();
        final long doOutro = saida ? d.targetPk() : d.sourcePk();
        if (deste == elemento.pk() && doOutro != elemento.pk()) {
          porOutro.computeIfAbsent(doOutro, chave -> new ArrayList<>()).add(d);
        }
      }
      for (final Map.Entry<Long, List<Dependencia>> par : porOutro.entrySet()) {
        linhas.add(linhaDeTipo(elemento, elemento(par.getKey()), saida, rotulos, par.getValue()));
      }
    }
    linhas.sort(ORDEM_DA_PAGINA);
    return List.copyOf(linhas);
  }

  private static final Comparator<ObjectNode> ORDEM_DA_PAGINA =
      Comparator.<ObjectNode>comparingInt(l -> l.path("isExternal").asBoolean() ? 1 : 0)
          .thenComparing(l -> -l.path("weight").asInt())
          .thenComparing(l -> l.path("elementId").asText(), OraculoDoCache::porPontoDeCodigo);

  private ObjectNode linhaDeTipo(
      Elemento este,
      Elemento outro,
      boolean saida,
      Map<String, String> rotulos,
      List<Dependencia> doPar) {
    final Map<String, Integer> tipos = new TreeMap<>();
    int peso = 0;
    String resolucao = FORCA.get(0);
    boolean ambigua = false;
    for (final Dependencia d : doPar) {
      tipos.merge(d.type(), d.occurrences(), Integer::sum);
      peso += DependencyAggregator.weightOf(DependencyType.valueOf(d.type())) * d.occurrences();
      resolucao = maisFraca(resolucao, d.resolution());
      ambigua |= d.ambiguous();
    }
    return linha(este, outro, saida, rotulos, tipos, peso, resolucao, ambigua);
  }

  private ObjectNode linha(
      Elemento este,
      Elemento outro,
      boolean saida,
      Map<String, String> rotulos,
      Map<String, Integer> tipos,
      int peso,
      String resolucao,
      boolean ambigua) {
    final ObjectNode linha = JSON.createObjectNode();
    linha.put("ambiguous", ambigua);
    linha.put("edgeId", saida ? edgeId(este.id(), outro.id()) : edgeId(outro.id(), este.id()));
    linha.put("elementId", outro.id());
    linha.put("isExternal", outro.external());
    linha.put("kind", outro.kind());
    linha.put("label", outro.external() ? outro.qualifiedName() : rotulo(rotulos, outro.id()));
    final boolean tipoInterno = outro.interno() && "TYPE".equals(outro.kind());
    if (tipoInterno) {
      linha.put("packageId", idDoPai(outro));
    } else {
      linha.putNull("packageId");
    }
    linha.put("qualifiedName", outro.qualifiedName());
    linha.put("resolution", resolucao);
    final ObjectNode porTipo = linha.putObject("types");
    tipos.forEach(porTipo::put);
    linha.put("weight", peso);
    return linha;
  }

  private static String rotulo(Map<String, String> rotulos, String id) {
    return Optional.ofNullable(rotulos.get(id))
        .orElseThrow(() -> new AssertionError("o outline não deu rótulo a " + id));
  }

  ArrayNode metodosEsperados(Elemento tipo) {
    final List<Elemento> filhos =
        metodos().stream()
            .filter(m -> m.parentPk() != null && m.parentPk() == tipo.pk())
            .sorted(
                Comparator.comparing(
                        (Elemento m) -> m.startLine() == null ? Integer.MAX_VALUE : m.startLine())
                    .thenComparing(Elemento::id, OraculoDoCache::porPontoDeCodigo))
            .toList();
    final ArrayNode lista = JSON.createArrayNode();
    for (final Elemento metodo : filhos) {
      final ObjectNode item = lista.addObject();
      final Long cc = metrica(metodo).map(Metrica::ccSum).orElse(null);
      if (cc == null) {
        item.putNull("cc");
      } else {
        item.put("cc", cc.intValue());
      }
      item.put("elementId", metodo.id());
      item.set("lines", linhas(metodo));
      item.put("signature", assinatura(metodo.id(), tipo.simpleName()));
    }
    return lista;
  }

  ArrayNode evidenciasEsperadas(Elemento origem, Elemento destino, Map<String, String> rotulos) {
    final Map<Long, Dependencia> doPar = new HashMap<>();
    linhasDoPar(origem, destino).forEach(d -> doPar.put(d.pk(), d));
    final List<Evidencia> doParOrdenadas =
        evidencias.stream()
            .filter(v -> doPar.containsKey(v.dependencyPk()))
            .sorted(
                Comparator.comparingInt(
                        (Evidencia v) ->
                            DependencyType.valueOf(doPar.get(v.dependencyPk()).type()).ordinal())
                    .thenComparing(v -> v.line() == null ? Integer.MAX_VALUE : v.line())
                    .thenComparing(v -> idDoMetodo(v.fromMethodPk()), OraculoDoCache::nuloPrimeiro)
                    .thenComparing(v -> idDoMetodo(v.toMethodPk()), OraculoDoCache::nuloPrimeiro)
                    .thenComparingInt(v -> ResolutionQuality.valueOf(v.resolution()).ordinal())
                    .thenComparing(Evidencia::filePath, OraculoDoCache::nuloPrimeiro))
            .toList();
    final ArrayNode lista = JSON.createArrayNode();
    for (final Evidencia v : doParOrdenadas) {
      final ObjectNode item = lista.addObject();
      item.put("filePath", v.filePath());
      item.set("fromMethod", referencia(v.fromMethodPk(), rotulos));
      if (v.line() == null) {
        item.putNull("line");
      } else {
        item.put("line", v.line().intValue());
      }
      item.put("resolution", v.resolution());
      item.set("toMethod", referencia(v.toMethodPk(), rotulos));
      item.put("type", doPar.get(v.dependencyPk()).type());
    }
    return lista;
  }

  int linhasDePaginaNoTotal() {
    final Map<List<Long>, Boolean> pares = paresQueNaoSaoLaco();
    final long internos = pares.values().stream().filter(Boolean::booleanValue).count();
    return pares.size() + (int) internos + 2 * arestasDePacote.size();
  }

  int paresDeTipos() {
    return paresQueNaoSaoLaco().size();
  }

  private Map<List<Long>, Boolean> paresQueNaoSaoLaco() {
    final Map<List<Long>, Boolean> pares = new HashMap<>();
    for (final Dependencia d : dependencias) {
      if (d.sourcePk() != d.targetPk()) {
        pares.put(List.of(d.sourcePk(), d.targetPk()), elemento(d.targetPk()).interno());
      }
    }
    return pares;
  }

  long evidenciasGravadas(Elemento origem, Elemento destino) {
    final List<Long> doPar = linhasDoPar(origem, destino).stream().map(Dependencia::pk).toList();
    return evidencias.stream().filter(v -> doPar.contains(v.dependencyPk())).count();
  }

  private String idDoMetodo(Long pk) {
    return pk == null ? null : elemento(pk).id();
  }

  private JsonNode referencia(Long metodoPk, Map<String, String> rotulos) {
    if (metodoPk == null) {
      return JSON.nullNode();
    }
    final Elemento metodo = elemento(metodoPk);
    final Elemento dono = elemento(metodo.parentPk());
    final ObjectNode referencia = JSON.createObjectNode();
    referencia.put("elementId", metodo.id());
    referencia.put("ownerId", dono.id());
    referencia.put("ownerLabel", rotulo(rotulos, dono.id()));
    referencia.put("signature", assinatura(metodo.id(), dono.simpleName()));
    return referencia;
  }

  static JsonNode linhas(Elemento elemento) {
    if (elemento.startLine() == null) {
      return JSON.nullNode();
    }
    return JSON.createObjectNode()
        .put("end", elemento.endLine().intValue())
        .put("start", elemento.startLine().intValue());
  }

  static String assinatura(String metodoId, String nomeDoDono) {
    final String semTipo = metodoId.substring(metodoId.indexOf('#') + 1);
    final int abre = semTipo.indexOf('(');
    final String nome = semTipo.substring(0, abre);
    final String parametros = semTipo.substring(abre + 1, semTipo.length() - 1);
    final List<String> simples =
        parametros.isEmpty()
            ? List.of()
            : Arrays.stream(parametros.split(",", -1))
                .map(p -> p.substring(p.lastIndexOf('.') + 1))
                .toList();
    final String exibido = "<init>".equals(nome) ? nomeDoDono : nome;
    return exibido + "(" + String.join(", ", simples) + ")";
  }

  static BigDecimal mediaEsperada(long ccSum, long unitCount) {
    if (unitCount == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(ccSum).divide(BigDecimal.valueOf(unitCount), 1, RoundingMode.HALF_UP);
  }

  static String edgeId(String origem, String destino) {
    long hash = FNV_BASE;
    final byte[] bytes = (origem + Character.toString(0) + destino).getBytes(UTF_8);
    for (final byte b : bytes) {
      hash ^= b & 0xff;
      hash *= FNV_PRIMO;
    }
    return "e_" + String.format(Locale.ROOT, "%016x", hash);
  }

  static String maisFraca(String uma, String outra) {
    return FORCA.indexOf(uma) >= FORCA.indexOf(outra) ? uma : outra;
  }

  static int porPontoDeCodigo(String um, String outro) {
    return Arrays.compare(um.codePoints().toArray(), outro.codePoints().toArray());
  }

  private static int nuloPrimeiro(String um, String outro) {
    if (um == null) {
      return outro == null ? 0 : -1;
    }
    if (outro == null) {
      return 1;
    }
    return porPontoDeCodigo(um, outro);
  }
}
