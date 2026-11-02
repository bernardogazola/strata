package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.persistence.CacheDiscardException;
import br.pucpr.tcc.strata.analyzer.persistence.Migrations;
import br.pucpr.tcc.strata.analyzer.persistence.RepositoryCache;
import br.pucpr.tcc.strata.analyzer.persistence.SqlWork;
import br.pucpr.tcc.strata.analyzer.persistence.SqliteConnections;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CodeMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.CodeMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.GraphMetricRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.PackageEdgeRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StructureCaches implements AutoCloseable {

  static final SliceBudget PACKAGE_BUDGET =
      new SliceBudget(
          AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_NODES,
          AnalysisConstants.GRAPH_SLICE_PACKAGE_MAX_EDGES);

  static final SliceBudget TYPE_BUDGET =
      new SliceBudget(
          AnalysisConstants.GRAPH_SLICE_TYPE_MAX_NODES,
          AnalysisConstants.GRAPH_SLICE_TYPE_MAX_EDGES);

  static final Comparator<String> CODE_POINT_ORDER =
      (left, right) -> Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());

  static final Map<DependencyType, Integer> TYPE_WEIGHTS =
      Map.of(
          DependencyType.EXTENDS, 4,
          DependencyType.IMPLEMENTS, 4,
          DependencyType.FIELD_TYPE, 3,
          DependencyType.CALLS, 2,
          DependencyType.RETURN_TYPE, 2,
          DependencyType.PARAMETER_TYPE, 1,
          DependencyType.ANNOTATION_REFERENCE, 1,
          DependencyType.IMPORTS, 1);

  static final List<DependencyType> EDGE_TYPES =
      Arrays.stream(DependencyType.values()).filter(DependencyType::isTypeEdge).toList();

  static final List<String> APP_PACKAGES = appPackages();

  private static final List<AppEdge> APP_EDGES =
      List.of(
          new AppEdge(
              "app.web",
              "app.data",
              2,
              ResolutionQuality.RESOLVED,
              occurrences(DependencyType.CALLS, 3)),
          new AppEdge(
              "app.web",
              "app.util.u000",
              1,
              ResolutionQuality.INFERRED,
              occurrences(DependencyType.IMPORTS, 1)),
          new AppEdge(
              "app.web",
              "app.util.u001",
              1,
              ResolutionQuality.RESOLVED,
              occurrences(DependencyType.FIELD_TYPE, 2)),
          new AppEdge(
              "app.data.p000",
              "app.util.u005",
              3,
              ResolutionQuality.RESOLVED,
              occurrences(DependencyType.CALLS, 2, DependencyType.RETURN_TYPE, 1)),
          new AppEdge(
              "app.util.u000",
              "app.util.u001",
              1,
              ResolutionQuality.RESOLVED,
              occurrences(DependencyType.CALLS, 1)),
          new AppEdge(
              "app.util.u002",
              "app.data",
              1,
              ResolutionQuality.RESOLVED,
              occurrences(DependencyType.EXTENDS, 1)),
          new AppEdge(
              "app.data",
              "app.data.p000",
              1,
              ResolutionQuality.INFERRED,
              occurrences(DependencyType.IMPLEMENTS, 1)),
          new AppEdge(
              "app.util.u003",
              "app.data",
              2,
              ResolutionQuality.INFERRED,
              occurrences(DependencyType.EXTENDS, 1, DependencyType.CALLS, 2)));

  private static final InstantSource CLOCK =
      InstantSource.fixed(Instant.ofEpochMilli(1_792_000_000_000L));

  private static final String INSERT_ANALYSIS =
      """
      INSERT INTO analysis (analysis_id, status, started_at, finished_at, analyzer_version,
                            schema_version, protocol_version, config_hash, config_json,
                            include_tests, history_scope)
      VALUES (?, 'COMPLETED', ?, ?, '0.1.0', 1, 1, ?, '{}', 0, 'ALL')""";

  private static final String FIND_ANALYSIS = "SELECT id FROM analysis WHERE analysis_id = ?";

  private final SqliteConnections connections;
  private long nextElementPk = 1;
  private long nextDependencyPk = 1;
  private long nextSourceFilePk = 1;

  private StructureCaches(SqliteConnections connections) {
    this.connections = connections;
  }

  public static StructureCaches create(Path directory) throws SQLException, CacheDiscardException {
    final SqliteConnections connections =
        SqliteConnections.open(directory.resolve(RepositoryCache.DATABASE_FILE));
    try {
      connections.<Integer, CacheDiscardException>withWriter(
          writer -> Migrations.bundled().migrate(writer, CLOCK));
      return new StructureCaches(connections);
    } catch (SQLException | CacheDiscardException | RuntimeException failure) {
      try {
        connections.close();
      } catch (SQLException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public SyntheticAnalysis analysis(String analysisId) throws SQLException {
    final long pk =
        connections.write(
            c -> {
              try (PreparedStatement insert = c.prepareStatement(INSERT_ANALYSIS)) {
                insert.setString(1, analysisId);
                insert.setLong(2, CLOCK.millis());
                insert.setLong(3, CLOCK.millis());
                insert.setString(4, "c".repeat(64));
                insert.executeUpdate();
              }
              try (PreparedStatement find = c.prepareStatement(FIND_ANALYSIS)) {
                find.setString(1, analysisId);
                try (ResultSet rows = find.executeQuery()) {
                  rows.next();
                  return rows.getLong(1);
                }
              }
            });
    return new SyntheticAnalysis(pk);
  }

  public <T> T read(SqlWork<T> work) throws SQLException {
    return connections.read(work);
  }

  void write(SqlWork<?> work) throws SQLException {
    connections.write(work);
  }

  @Override
  public void close() throws SQLException {
    connections.close();
  }

  static double appPagerank(int index) {
    return (index + 1) / 45_451.0;
  }

  static int appLoc(int index) {
    return 10 + index % 7;
  }

  static ResolutionMix appMix(int index) {
    return mix(index % 4, index % 3, index % 2);
  }

  SyntheticAnalysis appWorld(String analysisId) throws SQLException {
    final Map<String, Set<String>> into = new HashMap<>();
    final Map<String, Set<String>> outOf = new HashMap<>();
    for (final AppEdge edge : APP_EDGES) {
      outOf.computeIfAbsent(edge.source(), name -> new HashSet<>()).add(edge.target());
      into.computeIfAbsent(edge.target(), name -> new HashSet<>()).add(edge.source());
    }
    final SyntheticAnalysis world = analysis(analysisId);
    final Map<String, Long> pks = new HashMap<>();
    for (int i = 0; i < APP_PACKAGES.size(); i++) {
      final String name = APP_PACKAGES.get(i);
      final int fanIn = into.getOrDefault(name, Set.of()).size();
      final int fanOut = outOf.getOrDefault(name, Set.of()).size();
      pks.put(
          name,
          world.addPackage(
              name, new NodeValues(appLoc(i), fanIn, fanOut, appPagerank(i), appMix(i))));
    }
    for (final String name : APP_PACKAGES) {
      world.addType(pks.get(name), "T", NodeValues.PLAIN);
    }
    world.addType(pks.get("app.util.u000"), "T$Inner", NodeValues.PLAIN);
    for (final AppEdge edge : APP_EDGES) {
      world.addPackageEdge(
          pks.get(edge.source()),
          pks.get(edge.target()),
          edge.typePairCount(),
          edge.resolution(),
          edge.occurrences());
    }
    return world.write();
  }

  private static List<String> appPackages() {
    final List<String> names = new ArrayList<>();
    names.add("app.data");
    for (int k = 0; k < 150; k++) {
      names.add("app.data.p%03d".formatted(k));
    }
    for (int k = 0; k < 149; k++) {
      names.add("app.util.u%03d".formatted(k));
    }
    names.add("app.web");
    return List.copyOf(names);
  }

  private record AppEdge(
      String source,
      String target,
      int typePairCount,
      ResolutionQuality resolution,
      Map<DependencyType, Integer> occurrences) {}

  static ResolutionMix mix(long resolved, long inferred, long unresolvedExternal) {
    return new ResolutionMix(resolved, inferred, unresolvedExternal);
  }

  static Map<DependencyType, Long> types(Object... pairs) {
    final Map<DependencyType, Long> types = new EnumMap<>(DependencyType.class);
    for (int i = 0; i < pairs.length; i += 2) {
      types.put((DependencyType) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return types;
  }

  static Map<DependencyType, Integer> occurrences(Object... pairs) {
    final Map<DependencyType, Integer> occurrences = new EnumMap<>(DependencyType.class);
    for (int i = 0; i < pairs.length; i += 2) {
      occurrences.put((DependencyType) pairs[i], (Integer) pairs[i + 1]);
    }
    return occurrences;
  }

  public record NodeValues(int loc, int fanIn, int fanOut, double pagerank, ResolutionMix mix) {

    public static final NodeValues PLAIN = new NodeValues(1, 0, 0, 0.001, ResolutionMix.NONE);
  }

  record Metrics(int loc, Integer unitCount, Integer ccSum, Integer ccMax, Integer ccP90) {

    static Metrics withoutCc(int loc) {
      return new Metrics(loc, 0, null, null, null);
    }

    static Metrics oneUnit(int loc, int cc) {
      return new Metrics(loc, 1, cc, cc, null);
    }
  }

  record GraphValues(
      int fanIn, int fanOut, int fanOutExternal, double pagerank, Cycle cycle, ResolutionMix mix) {}

  record Declaration(
      TypeKind typeKind,
      String modifiers,
      boolean isTest,
      Long filePk,
      Integer startLine,
      Integer endLine) {}

  public final class SyntheticAnalysis {

    private final long pk;
    private final List<ElementRow> packages = new ArrayList<>();
    private final List<ElementRow> types = new ArrayList<>();
    private final List<ElementRow> members = new ArrayList<>();
    private final List<CodeMetricRow> codeMetrics = new ArrayList<>();
    private final List<GraphMetricRow> graphMetrics = new ArrayList<>();
    private final List<PackageEdgeRow> packageEdges = new ArrayList<>();
    private final List<DependencyRow> dependencies = new ArrayList<>();
    private final List<SourceFileRow> sourceFiles = new ArrayList<>();
    private final List<EvidenceRow> evidence = new ArrayList<>();
    private final Map<Long, String> qualifiedNames = new HashMap<>();
    private boolean written;

    private SyntheticAnalysis(long pk) {
      this.pk = pk;
    }

    public long pk() {
      return pk;
    }

    public long addPackage(String qualifiedName, NodeValues values) {
      final long elementPk = addBarePackage(qualifiedName);
      codeMetrics.add(new CodeMetricRow(elementPk, values.loc(), null, null, null, null));
      graphMetrics.add(
          new GraphMetricRow(
              elementPk,
              ElementKind.PACKAGE,
              values.fanIn(),
              values.fanOut(),
              null,
              null,
              null,
              null,
              values.pagerank(),
              values.pagerank(),
              null,
              null,
              null,
              1,
              0,
              null,
              values.mix().resolved(),
              values.mix().inferred(),
              values.mix().unresolvedExternal()));
      return elementPk;
    }

    long addBarePackage(String qualifiedName) {
      final long elementPk = nextElementPk++;
      final String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
      packages.add(
          element(
              elementPk,
              CodeElementId.ofPackage(qualifiedName),
              null,
              simpleName,
              qualifiedName,
              false));
      qualifiedNames.put(elementPk, qualifiedName);
      return elementPk;
    }

    public long addType(long packagePk, String binaryName, NodeValues values) {
      final long elementPk = nextElementPk++;
      final String packageName = qualifiedNames.get(packagePk);
      final String dotted = binaryName.replace('$', '.');
      final String prefix = packageName.isEmpty() ? "" : packageName + ".";
      types.add(
          element(
              elementPk,
              CodeElementId.ofType(prefix + binaryName),
              packagePk,
              binaryName.substring(binaryName.lastIndexOf('$') + 1),
              prefix + dotted,
              false));
      qualifiedNames.put(elementPk, prefix + binaryName);
      codeMetrics.add(new CodeMetricRow(elementPk, values.loc(), null, null, null, null));
      graphMetrics.add(
          new GraphMetricRow(
              elementPk,
              ElementKind.TYPE,
              values.fanIn(),
              values.fanOut(),
              values.fanIn(),
              values.fanOut(),
              0,
              0,
              values.pagerank(),
              values.pagerank(),
              null,
              null,
              0,
              null,
              null,
              null,
              values.mix().resolved(),
              values.mix().inferred(),
              values.mix().unresolvedExternal()));
      return elementPk;
    }

    long addExternalType(String qualifiedName) {
      final long elementPk = nextElementPk++;
      types.add(
          element(
              elementPk,
              CodeElementId.ofType(qualifiedName),
              null,
              qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
              qualifiedName,
              true));
      return elementPk;
    }

    long addMethod(long typePk, String name) {
      final long elementPk = nextElementPk++;
      final String owner = qualifiedNames.get(typePk);
      members.add(
          element(
              elementPk,
              CodeElementId.ofMethod(owner, name, List.of()),
              typePk,
              name,
              owner.replace('$', '.') + "." + name,
              false));
      return elementPk;
    }

    void addPackageEdge(
        long sourcePk,
        long targetPk,
        int typePairCount,
        ResolutionQuality resolution,
        Map<DependencyType, Integer> occurrences) {
      final int total = occurrences.values().stream().mapToInt(Integer::intValue).sum();
      packageEdges.add(
          new PackageEdgeRow(sourcePk, targetPk, typePairCount, total, resolution, occurrences));
    }

    void addDependency(
        long sourcePk,
        long targetPk,
        DependencyType type,
        ResolutionQuality resolution,
        int count) {
      dependencies.add(
          new DependencyRow(
              nextDependencyPk++,
              sourcePk,
              targetPk,
              type,
              resolution,
              count,
              TYPE_WEIGHTS.get(type) * count,
              false));
    }

    public SyntheticAnalysis write() throws SQLException {
      if (written) {
        throw new IllegalStateException("gravado duas vezes: " + pk);
      }
      written = true;
      connections.write(
          c -> {
            writeAll(c);
            return null;
          });
      return this;
    }

    private void writeAll(Connection c) throws SQLException {
      SourceFileDao.insert(c, pk, sourceFiles);
      ElementDao.insert(c, pk, packages);
      ElementDao.insert(c, pk, types);
      ElementDao.insert(c, pk, members);
      CodeMetricDao.insert(c, pk, codeMetrics);
      GraphMetricDao.insert(c, pk, graphMetrics);
      PackageEdgeDao.insert(c, pk, packageEdges);
      DependencyDao.insert(c, pk, dependencies);
      EvidenceDao.insert(c, evidence);
    }

    long addSourceFile(String relativePath) {
      final long filePk = nextSourceFilePk++;
      sourceFiles.add(new SourceFileRow(filePk, relativePath, 1_000, 0, SourceFileStatus.ANALYZED));
      return filePk;
    }

    long addPackage(String qualifiedName, boolean isTest, Metrics metrics, GraphValues graph) {
      final long elementPk = nextElementPk++;
      final CodeElementId id = CodeElementId.ofPackage(qualifiedName);
      packages.add(
          declared(
              elementPk,
              id,
              null,
              qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
              qualifiedName,
              new Declaration(null, "", isTest, null, null, null)));
      qualifiedNames.put(elementPk, qualifiedName);
      addMetricRows(elementPk, ElementKind.PACKAGE, metrics, graph);
      return elementPk;
    }

    long addType(
        long packagePk,
        String binaryName,
        Declaration declaration,
        Metrics metrics,
        GraphValues graph) {
      final long elementPk = nextElementPk++;
      final String packageName = qualifiedNames.get(packagePk);
      final String prefix = packageName.isEmpty() ? "" : packageName + ".";
      types.add(
          declared(
              elementPk,
              CodeElementId.ofType(prefix + binaryName),
              packagePk,
              binaryName.substring(binaryName.lastIndexOf('$') + 1),
              prefix + binaryName.replace('$', '.'),
              declaration));
      qualifiedNames.put(elementPk, prefix + binaryName);
      addMetricRows(elementPk, ElementKind.TYPE, metrics, graph);
      return elementPk;
    }

    long addMethod(
        long typePk,
        String name,
        List<String> parameters,
        Integer startLine,
        Integer endLine,
        Metrics metrics) {
      final long elementPk = nextElementPk++;
      final String owner = qualifiedNames.get(typePk);
      members.add(
          declared(
              elementPk,
              CodeElementId.ofMethod(owner, name, parameters),
              typePk,
              name,
              owner.replace('$', '.') + "." + name,
              new Declaration(null, "", false, null, startLine, endLine)));
      if (metrics != null) {
        codeMetrics.add(
            new CodeMetricRow(
                elementPk,
                metrics.loc(),
                metrics.unitCount(),
                metrics.ccSum(),
                metrics.ccMax(),
                metrics.ccP90()));
      }
      return elementPk;
    }

    public long addDependency(
        long sourcePk,
        long targetPk,
        DependencyType type,
        ResolutionQuality resolution,
        int count,
        boolean ambiguous) {
      final long dependencyPk = nextDependencyPk++;
      dependencies.add(
          new DependencyRow(
              dependencyPk,
              sourcePk,
              targetPk,
              type,
              resolution,
              count,
              TYPE_WEIGHTS.get(type) * count,
              ambiguous));
      return dependencyPk;
    }

    void addEvidence(
        long dependencyPk,
        Long fromMethodPk,
        Long toMethodPk,
        Long filePk,
        Integer line,
        ResolutionQuality resolution) {
      evidence.add(
          new EvidenceRow(dependencyPk, fromMethodPk, toMethodPk, filePk, line, resolution));
    }

    private void addMetricRows(
        long elementPk, ElementKind granularity, Metrics metrics, GraphValues graph) {
      if (metrics != null) {
        codeMetrics.add(
            new CodeMetricRow(
                elementPk,
                metrics.loc(),
                metrics.unitCount(),
                metrics.ccSum(),
                metrics.ccMax(),
                metrics.ccP90()));
      }
      if (graph != null) {
        graphMetrics.add(graphRow(elementPk, granularity, graph));
      }
    }

    private static GraphMetricRow graphRow(
        long elementPk, ElementKind granularity, GraphValues graph) {
      final boolean type = granularity == ElementKind.TYPE;
      final Integer sccId = graph.cycle() == null ? null : graph.cycle().id();
      final Integer sccSize = graph.cycle() == null ? null : graph.cycle().size();
      return new GraphMetricRow(
          elementPk,
          granularity,
          graph.fanIn(),
          graph.fanOut(),
          type ? graph.fanIn() : null,
          type ? graph.fanOut() : null,
          type ? graph.fanOutExternal() : null,
          type ? 0 : null,
          graph.pagerank(),
          graph.pagerank(),
          sccId,
          sccSize,
          type ? 0 : null,
          type ? null : 1,
          type ? null : 0,
          null,
          graph.mix().resolved(),
          graph.mix().inferred(),
          graph.mix().unresolvedExternal());
    }

    private static ElementRow declared(
        long elementPk,
        CodeElementId id,
        Long parentPk,
        String simpleName,
        String qualifiedName,
        Declaration declaration) {
      return new ElementRow(
          elementPk,
          id,
          id.kind(),
          parentPk,
          simpleName,
          qualifiedName,
          id.sortKey(),
          declaration.typeKind(),
          declaration.modifiers(),
          false,
          declaration.isTest(),
          null,
          declaration.filePk(),
          declaration.startLine(),
          declaration.endLine());
    }

    private static ElementRow element(
        long elementPk,
        CodeElementId id,
        Long parentPk,
        String simpleName,
        String qualifiedName,
        boolean external) {
      return new ElementRow(
          elementPk,
          id,
          id.kind(),
          parentPk,
          simpleName,
          qualifiedName,
          id.sortKey(),
          id.kind() == ElementKind.TYPE ? TypeKind.CLASS : null,
          "",
          external,
          false,
          null,
          null,
          null,
          null);
    }
  }
}
