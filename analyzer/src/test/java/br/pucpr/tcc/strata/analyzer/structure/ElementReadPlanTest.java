package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.CALLS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.FIELD_TYPE;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static br.pucpr.tcc.strata.analyzer.structure.StructureCaches.occurrences;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.persistence.SqlWork;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyPageDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EdgeEvidenceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDetailDao;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Declaration;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.GraphValues;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.Metrics;
import br.pucpr.tcc.strata.analyzer.structure.StructureCaches.SyntheticAnalysis;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ElementReadPlanTest {

  private static final String PAR_PELA_ANALISE =
      "sqlite_autoindex_dependency_1 (analysis_pk=? AND source_pk=? AND target_pk=?)";

  private static final String ORIGEM_PELA_ANALISE =
      "sqlite_autoindex_dependency_1 (analysis_pk=? AND source_pk=?";

  private static final Pattern SO_A_ANALISE = Pattern.compile("\\(analysis_pk=\\?\\)");

  private static final Pattern TABELA =
      Pattern.compile(
          "(?i)\\b(?:FROM|JOIN)\\s+(code_element|dependency_evidence|dependency|source_file"
              + "|code_metric|graph_metric|package_edge)\\b(?:\\s+(?:AS\\s+)?(\\w+))?");

  private static final Pattern DEPENDENCY = Pattern.compile("(?i)\\bdependency\\b");

  @TempDir private Path temp;

  private StructureCaches cache;

  @BeforeEach
  void abrir() throws Exception {
    cache = StructureCaches.create(temp);
  }

  @AfterEach
  void fechar() throws SQLException {
    cache.close();
  }

  private record Ligacao(Method setter, Object[] argumentos) {}

  private record Execucao(String sql, List<Ligacao> ligacoes) {}

  private static final class Gravador {

    private final List<Execucao> execucoes = new ArrayList<>();
    private boolean semPreparo;

    Connection embrulha(Connection real) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, metodo, argumentos) -> {
                if (metodo.getName().equals("createStatement")
                    || metodo.getName().equals("prepareCall")) {
                  semPreparo = true;
                }
                final Object resultado = chama(metodo, real, argumentos);
                if (metodo.getName().equals("prepareStatement")
                    && resultado instanceof PreparedStatement preparado) {
                  return embrulha(preparado, (String) argumentos[0]);
                }
                return resultado;
              });
    }

    private PreparedStatement embrulha(PreparedStatement real, String sql) {
      final Map<Integer, Ligacao> ligadas = new TreeMap<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              PreparedStatement.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, metodo, argumentos) -> {
                final String nome = metodo.getName();
                if (nome.startsWith("set")
                    && argumentos != null
                    && argumentos.length >= 2
                    && argumentos[0] instanceof Integer indice) {
                  ligadas.put(indice, new Ligacao(metodo, argumentos.clone()));
                } else if (nome.equals("clearParameters")) {
                  ligadas.clear();
                } else if (nome.startsWith("execute")) {
                  execucoes.add(new Execucao(sql, List.copyOf(ligadas.values())));
                }
                return chama(metodo, real, argumentos);
              });
    }

    private static Object chama(Method metodo, Object alvo, Object[] argumentos) throws Throwable {
      try {
        return metodo.invoke(alvo, argumentos);
      } catch (InvocationTargetException falha) {
        throw falha.getCause();
      }
    }
  }

  private Gravador grava(SqlWork<?> chamada) throws SQLException {
    final Gravador gravador = new Gravador();
    cache.read(c -> chamada.run(gravador.embrulha(c)));
    assertThat(gravador.semPreparo).as("um Statement sem parâmetro").isFalse();
    assertThat(gravador.execucoes).as("a chamada executou alguma instrução").isNotEmpty();
    return gravador;
  }

  private List<String> plano(Execucao execucao) throws SQLException {
    return cache.read(
        c -> {
          try (PreparedStatement explain =
              c.prepareStatement("EXPLAIN QUERY PLAN " + execucao.sql())) {
            for (final Ligacao ligacao : execucao.ligacoes()) {
              ligacao.setter().invoke(explain, ligacao.argumentos());
            }
            final List<String> linhas = new ArrayList<>();
            try (ResultSet rows = explain.executeQuery()) {
              while (rows.next()) {
                linhas.add(rows.getString(4));
              }
            }
            return List.copyOf(linhas);
          } catch (ReflectiveOperationException falha) {
            throw new SQLException("não foi possível repetir um bind", falha);
          }
        });
  }

  private List<String> planos(Gravador gravador, boolean soDependency) throws SQLException {
    final List<String> linhas = new ArrayList<>();
    for (final Execucao execucao : gravador.execucoes) {
      if (!soDependency || DEPENDENCY.matcher(execucao.sql()).find()) {
        linhas.addAll(plano(execucao));
      }
    }
    return linhas;
  }

  private record Plano(long analise, long a, long b, long p) {}

  private Plano mundo() throws SQLException {
    final SyntheticAnalysis s = cache.analysis("an_plano");
    final long arquivo = s.addSourceFile("src/app/p/A.java");
    final Metrics metricas = new Metrics(10, 1, 1, 1, 1);
    final GraphValues grafo = new GraphValues(1, 1, 0, 0.25, null, ResolutionMix.NONE);
    final long p = s.addPackage("app.p", false, new Metrics(10, 1, 1, 1, null), grafo);
    final long q = s.addPackage("app.q", false, new Metrics(10, 1, 1, 1, null), grafo);
    final Declaration classe = new Declaration(TypeKind.CLASS, "public", false, arquivo, 1, 9);
    final long a = s.addType(p, "A", classe, metricas, grafo);
    final long b = s.addType(p, "B", classe, metricas, grafo);
    final long d = s.addType(q, "D", classe, metricas, grafo);
    final long string = s.addExternalType("java.lang.String");
    final long metodo = s.addMethod(a, "run", List.of(), 2, 3, Metrics.oneUnit(2, 1));
    final long chamada = s.addDependency(a, b, CALLS, RESOLVED, 1, false);
    s.addDependency(a, b, FIELD_TYPE, RESOLVED, 1, false);
    s.addDependency(b, a, CALLS, RESOLVED, 1, false);
    s.addDependency(a, d, CALLS, RESOLVED, 1, false);
    s.addDependency(d, a, CALLS, RESOLVED, 1, false);
    s.addDependency(a, string, FIELD_TYPE, RESOLVED, 1, false);
    s.addEvidence(chamada, metodo, null, arquivo, 2, RESOLVED);
    s.addPackageEdge(p, q, 1, RESOLVED, occurrences(CALLS, 1));
    s.addPackageEdge(q, p, 1, RESOLVED, occurrences(CALLS, 1));
    return new Plano(s.write().pk(), a, b, p);
  }

  @Test
  void oParExisteBuscaPelaAnaliseEPeloParInteiro() throws Exception {
    final Plano mundo = mundo();

    final Gravador gravador =
        grava(c -> EdgeEvidenceDao.pairExists(c, mundo.analise(), mundo.a(), mundo.b()));

    final List<String> linhas = planos(gravador, true);
    assertThat(linhas).anySatisfy(linha -> assertThat(linha).contains(PAR_PELA_ANALISE));
    assertThat(linhas)
        .allSatisfy(linha -> assertThat(linha).doesNotContain("idx_dep_in", "idx_dep_out"));
  }

  @Test
  void aEvidenciaDeUmParBuscaPelaAnaliseEPeloParInteiro() throws Exception {
    final Plano mundo = mundo();

    final Gravador gravador =
        grava(c -> EdgeEvidenceDao.evidence(c, mundo.analise(), mundo.a(), mundo.b()));

    final List<String> linhas = planos(gravador, true);
    assertThat(linhas).anySatisfy(linha -> assertThat(linha).contains(PAR_PELA_ANALISE));
    assertThat(linhas)
        .anySatisfy(linha -> assertThat(linha).contains("idx_dep_evidence (dependency_pk=?)"));
    assertThat(linhas)
        .allSatisfy(linha -> assertThat(linha).doesNotContain("idx_dep_in", "idx_dep_out"));
  }

  @Test
  void aContagemDeExternosBuscaPelaAnalise() throws Exception {
    final Plano mundo = mundo();

    final Gravador gravador =
        grava(c -> DependencyPageDao.typeTotals(c, mundo.analise(), mundo.a()));

    assertThat(planos(gravador, true))
        .anySatisfy(linha -> assertThat(linha).contains(ORIGEM_PELA_ANALISE));
  }

  @Test
  void nenhumaLeituraDaFase32PercorreAAnaliseInteira() throws Exception {
    final Plano mundo = mundo();
    final long analise = mundo.analise();
    final CodeElementId a = new CodeElementId("type:app.p.A");
    final Map<String, SqlWork<?>> chamadas = new TreeMap<>();
    chamadas.put("inbound", c -> DependencyPageDao.inbound(c, analise, mundo.a()));
    chamadas.put("outbound", c -> DependencyPageDao.outbound(c, analise, mundo.a()));
    chamadas.put("packageInbound", c -> DependencyPageDao.packageInbound(c, analise, mundo.p()));
    chamadas.put("packageOutbound", c -> DependencyPageDao.packageOutbound(c, analise, mundo.p()));
    chamadas.put("typeTotals", c -> DependencyPageDao.typeTotals(c, analise, mundo.a()));
    chamadas.put("packageTotals", c -> DependencyPageDao.packageTotals(c, analise, mundo.p()));
    chamadas.put("element", c -> ElementDetailDao.element(c, analise, a));
    chamadas.put("codeMetric", c -> ElementDetailDao.codeMetric(c, analise, mundo.a()));
    chamadas.put("graphMetric", c -> ElementDetailDao.graphMetric(c, analise, mundo.a()));
    chamadas.put("methods", c -> ElementDetailDao.methods(c, analise, mundo.a(), 1_000));
    chamadas.put("methodCount", c -> ElementDetailDao.methodCount(c, analise, mundo.a()));
    chamadas.put("pairExists", c -> EdgeEvidenceDao.pairExists(c, analise, mundo.a(), mundo.b()));
    chamadas.put("evidence", c -> EdgeEvidenceDao.evidence(c, analise, mundo.a(), mundo.b()));

    for (final Map.Entry<String, SqlWork<?>> chamada : chamadas.entrySet()) {
      final Gravador gravador = grava(chamada.getValue());
      for (final Execucao execucao : gravador.execucoes) {
        final Set<String> tabelas = tabelas(execucao.sql());
        for (final String linha : plano(execucao)) {
          assertThat(SO_A_ANALISE.matcher(linha).find())
              .as("%s: %s", chamada.getKey(), linha)
              .isFalse();
          assertThat(varre(linha, tabelas)).as("%s: %s", chamada.getKey(), linha).isFalse();
        }
      }
    }
  }

  private static Set<String> tabelas(String sql) {
    final Set<String> nomes = new HashSet<>();
    final Matcher matcher = TABELA.matcher(sql);
    while (matcher.find()) {
      nomes.add(matcher.group(1).toLowerCase(Locale.ROOT));
      if (matcher.group(2) != null) {
        nomes.add(matcher.group(2).toLowerCase(Locale.ROOT));
      }
    }
    return nomes;
  }

  private static boolean varre(String linha, Set<String> tabelas) {
    if (!linha.startsWith("SCAN ")) {
      return false;
    }
    final String alvo = linha.substring("SCAN ".length()).split(" ", 2)[0];
    return tabelas.contains(alvo.toLowerCase(Locale.ROOT));
  }
}
