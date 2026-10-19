package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Construct;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Dependencies;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Edges;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Elements;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Evidence;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.ExcludedConstructs;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.LargestRow;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Modules;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Spread;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Union;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitCensusPass;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DependencyReadBack {

  private static final int CAP = AnalysisConstants.DEPENDENCY_EVIDENCE_CAP;
  private static final double PERCENT = 100.0;
  private static final double HUNDREDTHS = 100.0;
  private static final double P50 = 0.50;
  private static final double P90 = 0.90;
  private static final double P99 = 0.99;
  private static final int PK_BITS = Integer.SIZE;

  private static final String ELEMENTS_BY_KIND =
      """
      SELECT kind, is_external, count(*) FROM code_element
       WHERE analysis_pk = ? GROUP BY kind, is_external""";

  private static final String FILE_STATUSES =
      "SELECT status, count(*) FROM source_file WHERE analysis_pk = ? GROUP BY status";

  private static final String SOURCE_ROOTS =
      "SELECT count(*) FROM source_root WHERE analysis_pk = ?";

  private static final String TYPES =
      """
      SELECT e.id, e.element_id, e.is_external, f.relative_path
        FROM code_element e LEFT JOIN source_file f ON f.id = e.file_pk
       WHERE e.analysis_pk = ? AND e.kind = 'TYPE'""";

  private static final String ROW_COUNT = "SELECT count(*) FROM dependency WHERE analysis_pk = ?";

  private static final String ROWS =
      """
      SELECT source_pk, target_pk, type, resolution, occurrences, weight
        FROM dependency WHERE analysis_pk = ?""";

  private static final String ENDPOINTS =
      """
      SELECT coalesce(sum(s.id IS NULL OR t.id IS NULL), 0),
             coalesce(sum(s.id IS NOT NULL AND (s.kind <> 'TYPE' OR s.is_external <> 0)), 0),
             coalesce(sum(t.id IS NOT NULL AND t.kind <> 'TYPE'), 0),
             coalesce(sum(d.type = 'MODULE_DEPENDENCY'), 0)
        FROM dependency d
        LEFT JOIN code_element s ON s.id = d.source_pk AND s.analysis_pk = d.analysis_pk
        LEFT JOIN code_element t ON t.id = d.target_pk AND t.analysis_pk = d.analysis_pk
       WHERE d.analysis_pk = ?""";

  private static final String EVIDENCE =
      """
      SELECT count(*),
             count(e.from_method_pk),
             coalesce(sum(e.line IS NULL), 0),
             count(e.to_method_pk),
             coalesce(sum(e.file_pk IS NOT s.file_pk), 0),
             coalesce(sum(m.id IS NOT NULL
                          AND (m.kind <> 'METHOD' OR m.parent_pk IS NOT d.source_pk)), 0),
             coalesce(sum(CASE e.resolution WHEN 'RESOLVED' THEN 2 WHEN 'INFERRED' THEN 1 ELSE 0 END
                        < CASE d.resolution WHEN 'RESOLVED' THEN 2 WHEN 'INFERRED' THEN 1 ELSE 0 END),
                      0),
             coalesce(sum(e.resolution = 'RESOLVED'), 0),
             coalesce(sum(e.resolution = 'INFERRED'), 0),
             coalesce(sum(e.resolution = 'UNRESOLVED_EXTERNAL'), 0)
        FROM dependency_evidence e
        JOIN dependency d ON d.id = e.dependency_pk
        JOIN code_element s ON s.id = d.source_pk
        LEFT JOIN code_element m ON m.id = e.from_method_pk
       WHERE d.analysis_pk = ?""";

  private static final String EVIDENCE_COUNT_MISMATCHES =
      """
      SELECT count(*) FROM dependency d
       WHERE d.analysis_pk = ?
         AND (SELECT count(*) FROM dependency_evidence e WHERE e.dependency_pk = d.id)
             <> min(d.occurrences, ?)""";

  private static final String EXTERNALS =
      """
      SELECT coalesce(sum(kind <> 'TYPE' OR parent_pk IS NOT NULL OR type_kind IS NOT NULL
                          OR source_root_pk IS NOT NULL OR file_pk IS NOT NULL
                          OR start_line IS NOT NULL OR end_line IS NOT NULL
                          OR modifiers <> '' OR is_test <> 0), 0),
             coalesce(sum(NOT EXISTS (SELECT 1 FROM dependency d WHERE d.target_pk = e.id)), 0)
        FROM code_element e
       WHERE e.analysis_pk = ? AND e.is_external = 1""";

  private static final String MODULES =
      """
      SELECT count(*), count(DISTINCT file_pk), count(DISTINCT module_name),
             coalesce(sum(is_transitive), 0), coalesce(sum(is_static), 0),
             coalesce(sum(line IS NULL), 0)
        FROM module_dependency WHERE analysis_pk = ?""";

  private static final String FOREIGN_KEY_CHECK = "PRAGMA foreign_key_check";

  private DependencyReadBack() {}

  static Result read(SqliteConnections connections, long analysisPk) throws SQLException {
    return connections.read(
        c -> {
          final Elements elements = elements(c, analysisPk);
          final Map<Long, TypeRow> types = types(c, analysisPk);
          final RowScan scan = scanRows(c, analysisPk, types);
          final long[] endpoints = longs(c, ENDPOINTS, analysisPk, 4);
          final long[] evidence = longs(c, EVIDENCE, analysisPk, 10);
          final long[] externals = longs(c, EXTERNALS, analysisPk, 2);
          final Integrity integrity =
              new Integrity(
                  foreignKeyViolations(c),
                  endpoints[0],
                  endpoints[1],
                  endpoints[2],
                  endpoints[3],
                  scan.invalidResolutionPairs(),
                  evidenceCountMismatches(c, analysisPk),
                  evidence[4],
                  evidence[5],
                  evidence[6],
                  evidence[3],
                  scan.weightMismatches(),
                  externals[0],
                  externals[1],
                  scan.edgeTotalsMatchRows());
          return new Result(
              elements,
              scan.dependencies(),
              evidence(evidence, scan),
              modules(c, analysisPk),
              scan.edges(),
              integrity,
              census(types, scan.basePairs()));
        });
  }

  private static Elements elements(Connection c, long analysisPk) throws SQLException {
    final Map<String, Long> internal = new HashMap<>();
    long externalTypes = 0;
    try (PreparedStatement statement = c.prepareStatement(ELEMENTS_BY_KIND)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          if (rows.getInt(2) == 0) {
            internal.put(rows.getString(1), rows.getLong(3));
          } else {
            externalTypes += rows.getLong(3);
          }
        }
      }
    }
    final Map<String, Long> found = grouped(c, FILE_STATUSES, analysisPk);
    final Map<String, Long> statuses = new LinkedHashMap<>();
    for (final SourceFileStatus status : SourceFileStatus.values()) {
      statuses.put(status.name(), found.getOrDefault(status.name(), 0L));
    }
    return new Elements(
        internal.getOrDefault("PACKAGE", 0L),
        internal.getOrDefault("TYPE", 0L),
        internal.getOrDefault("METHOD", 0L),
        internal.getOrDefault("FILE", 0L),
        externalTypes,
        statuses.values().stream().mapToLong(Long::longValue).sum(),
        longs(c, SOURCE_ROOTS, analysisPk, 1)[0],
        statuses);
  }

  private static Map<Long, TypeRow> types(Connection c, long analysisPk) throws SQLException {
    final Map<Long, TypeRow> types = new HashMap<>();
    try (PreparedStatement statement = c.prepareStatement(TYPES)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          final long pk = rows.getLong(1);
          if (pk >= 1L << (PK_BITS - 1)) {
            throw new IllegalStateException("um pk que não cabe em uma chave de par: " + pk);
          }
          types.put(
              pk,
              new TypeRow(
                  pk,
                  new CodeElementId(rows.getString(2)),
                  rows.getInt(3) == 1,
                  rows.getString(4)));
        }
      }
    }
    return types;
  }

  private static RowScan scanRows(Connection c, long analysisPk, Map<Long, TypeRow> types)
      throws SQLException {
    final int count = (int) longs(c, ROW_COUNT, analysisPk, 1)[0];
    final RowTally tally = new RowTally(count);
    final List<Dependency> rows = new ArrayList<>(count);
    try (PreparedStatement statement = c.prepareStatement(ROWS)) {
      statement.setLong(1, analysisPk);
      try (ResultSet found = statement.executeQuery()) {
        while (found.next()) {
          final TypeRow source = types.get(found.getLong(1));
          final TypeRow target = types.get(found.getLong(2));
          if (source == null || target == null) {
            continue;
          }
          final Dependency row =
              new Dependency(
                  source.id(),
                  target.id(),
                  DependencyType.valueOf(found.getString(3)),
                  ResolutionQuality.valueOf(found.getString(4)),
                  found.getInt(5));
          tally.add(row, source, target, found.getLong(6));
          rows.add(row);
        }
      }
    }
    final Map<String, TypeRow> byId = new HashMap<>();
    types.values().forEach(type -> byId.put(type.id().value(), type));
    final Edges edges = edges(DependencyAggregator.aggregate(rows), byId);
    return new RowScan(
        tally.dependencies(),
        tally.invalidResolutionPairs(),
        tally.weightMismatches,
        edges,
        edges.occurrenceTotal() == tally.occurrences && edges.weightTotal() == tally.weightTotal,
        tally.basePairs(),
        tally.rowsAtCap);
  }

  private static Edges edges(List<DependencyEdge> edges, Map<String, TypeRow> byId) {
    long self = 0;
    long toInternal = 0;
    long toExternal = 0;
    long occurrences = 0;
    long weight = 0;
    final Map<String, Long> byResolution = new LinkedHashMap<>();
    for (final ResolutionQuality quality : ResolutionQuality.values()) {
      byResolution.put(quality.name(), 0L);
    }
    final Map<String, Long> byTypeCount = new LinkedHashMap<>();
    for (int types = 1; types < DependencyType.values().length; types++) {
      byTypeCount.put(Integer.toString(types), 0L);
    }
    for (final DependencyEdge edge : edges) {
      if (edge.source().equals(edge.target())) {
        self++;
      } else if (byId.get(edge.target().value()).external()) {
        toExternal++;
      } else {
        toInternal++;
      }
      byResolution.merge(edge.resolution().name(), 1L, Long::sum);
      byTypeCount.merge(Integer.toString(edge.distinctTypeCount()), 1L, Long::sum);
      occurrences += edge.occurrenceTotal();
      weight += edge.weight();
    }
    return new Edges(
        edges.size(), self, toInternal, toExternal, byResolution, byTypeCount, occurrences, weight);
  }

  private static Evidence evidence(long[] evidence, RowScan scan) {
    final Map<String, Long> byResolution = new LinkedHashMap<>();
    byResolution.put(ResolutionQuality.RESOLVED.name(), evidence[7]);
    byResolution.put(ResolutionQuality.INFERRED.name(), evidence[8]);
    byResolution.put(ResolutionQuality.UNRESOLVED_EXTERNAL.name(), evidence[9]);
    final long uncapped = scan.dependencies().occurrences();
    return new Evidence(
        evidence[0],
        scan.rowsAtCap(),
        uncapped,
        uncapped == 0 ? 0 : hundredths(PERCENT * (uncapped - evidence[0]) / uncapped),
        evidence[1],
        evidence[2],
        byResolution);
  }

  private static long evidenceCountMismatches(Connection c, long analysisPk) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(EVIDENCE_COUNT_MISMATCHES)) {
      statement.setLong(1, analysisPk);
      statement.setInt(2, CAP);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static Modules modules(Connection c, long analysisPk) throws SQLException {
    final long[] modules = longs(c, MODULES, analysisPk, 6);
    return new Modules(modules[0], modules[1], modules[2], modules[3], modules[4], modules[5]);
  }

  private static CensusBase census(Map<Long, TypeRow> types, long[] basePairs) {
    final Map<String, TypeRow> byId = new HashMap<>();
    types.values().forEach(type -> byId.put(type.id().value(), type));
    long internal = 0;
    for (final long pair : basePairs) {
      if (!types.get(pair & ((1L << PK_BITS) - 1)).external()) {
        internal++;
      }
    }
    return new CensusBase(byId, basePairs, internal);
  }

  private static long pair(long sourcePk, long targetPk) {
    return (sourcePk << PK_BITS) | targetPk;
  }

  static double hundredths(double value) {
    return Math.round(value * HUNDREDTHS) / HUNDREDTHS;
  }

  private static Map<String, Long> grouped(Connection connection, String sql, long analysisPk)
      throws SQLException {
    final Map<String, Long> counts = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          counts.put(rows.getString(1), rows.getLong(2));
        }
      }
    }
    return counts;
  }

  private static long[] longs(Connection connection, String sql, long analysisPk, int columns)
      throws SQLException {
    final long[] values = new long[columns];
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, analysisPk);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        for (int column = 0; column < columns; column++) {
          values[column] = rows.getLong(column + 1);
        }
      }
    }
    return values;
  }

  private static long foreignKeyViolations(Connection connection) throws SQLException {
    long violations = 0;
    try (PreparedStatement statement = connection.prepareStatement(FOREIGN_KEY_CHECK);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        violations++;
      }
    }
    return violations;
  }

  record TypeRow(long pk, CodeElementId id, boolean external, String filePath) {}

  record Integrity(
      long foreignKeyViolations,
      long danglingEndpoints,
      long sourcesNotInternalTypes,
      long targetsNotTypes,
      long moduleDependencyInDependency,
      long invalidResolutionPairs,
      long evidenceCountMismatches,
      long evidenceFileMismatches,
      long fromMethodOwnerMismatches,
      long evidenceWeakerThanRow,
      long toMethodsWritten,
      long weightMismatches,
      long externalRowsMalformed,
      long externalsWithoutDependency,
      boolean edgeTotalsMatchRows) {}

  record CensusBase(Map<String, TypeRow> types, long[] basePairs, long baseInternalPairs) {}

  record Result(
      Elements elements,
      Dependencies dependencies,
      Evidence evidence,
      Modules modules,
      Edges edges,
      Integrity integrity,
      CensusBase census) {}

  private record RowScan(
      Dependencies dependencies,
      long invalidResolutionPairs,
      long weightMismatches,
      Edges edges,
      boolean edgeTotalsMatchRows,
      long[] basePairs,
      long rowsAtCap) {}

  private static final class RowTally {

    private final Map<DependencyType, Long> rowsByType = new EnumMap<>(DependencyType.class);
    private final Map<DependencyType, Long> occurrencesByType = new EnumMap<>(DependencyType.class);
    private final Map<String, Long> resolutionPairs = new LinkedHashMap<>();
    private final int[] perRow;
    private final long[] pairs;
    private int rows;
    private int pairCount;
    private long occurrences;
    private long weightTotal;
    private long weightMismatches;
    private long selfRows;
    private long selfOccurrences;
    private long rowsToExternal;
    private long occurrencesToExternal;
    private long rowsAtCap;
    private Dependency largest;

    private RowTally(int capacity) {
      perRow = new int[capacity];
      pairs = new long[capacity];
      for (final DependencyType type : DependencyType.values()) {
        if (type.isTypeEdge()) {
          rowsByType.put(type, 0L);
          occurrencesByType.put(type, 0L);
        }
      }
      for (final int external : new int[] {0, 1}) {
        for (final ResolutionQuality quality : ResolutionQuality.values()) {
          resolutionPairs.put(key(quality, external == 1), 0L);
        }
      }
    }

    private void add(Dependency row, TypeRow source, TypeRow target, long weight) {
      final int occurrencesOfRow = row.occurrences();
      perRow[rows++] = occurrencesOfRow;
      occurrences += occurrencesOfRow;
      weightTotal += weight;
      if (weight != DependencyAggregator.rowWeight(row)) {
        weightMismatches++;
      }
      rowsByType.merge(row.type(), 1L, Long::sum);
      occurrencesByType.merge(row.type(), (long) occurrencesOfRow, Long::sum);
      resolutionPairs.merge(key(row.resolution(), target.external()), 1L, Long::sum);
      if (occurrencesOfRow >= CAP) {
        rowsAtCap++;
      }
      if (target.external()) {
        rowsToExternal++;
        occurrencesToExternal += occurrencesOfRow;
      }
      if (source.pk() == target.pk()) {
        selfRows++;
        selfOccurrences += occurrencesOfRow;
      } else {
        pairs[pairCount++] = pair(source.pk(), target.pk());
      }
      if (largest == null || occurrencesOfRow > largest.occurrences()) {
        largest = row;
      }
    }

    private static String key(ResolutionQuality quality, boolean external) {
      return quality.name() + "," + (external ? 1 : 0);
    }

    private long invalidResolutionPairs() {
      return resolutionPairs.get(key(ResolutionQuality.INFERRED, true))
          + resolutionPairs.get(key(ResolutionQuality.UNRESOLVED_EXTERNAL, false));
    }

    private long[] basePairs() {
      final long[] sorted = Arrays.copyOf(pairs, pairCount);
      Arrays.sort(sorted);
      return Arrays.stream(sorted).distinct().toArray();
    }

    private Dependencies dependencies() {
      final Map<String, Long> byType = new LinkedHashMap<>();
      rowsByType.forEach((type, count) -> byType.put(type.name(), count));
      final Map<String, Long> occurrencesOf = new LinkedHashMap<>();
      occurrencesByType.forEach((type, count) -> occurrencesOf.put(type.name(), count));
      return new Dependencies(
          rows,
          occurrences,
          byType,
          occurrencesOf,
          selfRows,
          selfOccurrences,
          rowsToExternal,
          occurrencesToExternal,
          resolutionPairs,
          spread(),
          largest == null
              ? null
              : new LargestRow(
                  largest.source().value(),
                  largest.target().value(),
                  largest.type().name(),
                  largest.occurrences()));
    }

    private Spread spread() {
      if (rows == 0) {
        return new Spread(0, 0, 0, 0, 0);
      }
      final int[] sorted = Arrays.copyOf(perRow, rows);
      Arrays.sort(sorted);
      return new Spread(
          rank(sorted, P50),
          rank(sorted, P90),
          rank(sorted, P99),
          sorted[rows - 1],
          hundredths((double) occurrences / rows));
    }

    private static int rank(int[] sorted, double fraction) {
      return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }
  }

  static final class ExcludedPairs implements UnitCensusPass.Occurrences {

    private final CensusBase base;
    private final Map<UnitCensusPass.Construct, Pairs> pairs =
        new EnumMap<>(UnitCensusPass.Construct.class);
    private final Pairs union = new Pairs();

    ExcludedPairs(CensusBase base) {
      this.base = base;
      for (final UnitCensusPass.Construct construct : UnitCensusPass.Construct.values()) {
        pairs.put(construct, new Pairs());
      }
    }

    @Override
    public void accept(
        UnitCensusPass.Construct construct,
        String relativePath,
        CodeElementId source,
        CodeElementId target) {
      final Pairs counted = pairs.get(construct);
      final TypeRow from = base.types().get(source.value());
      if (from == null || from.external() || !relativePath.equals(from.filePath())) {
        counted.outside++;
        return;
      }
      counted.occurrences++;
      if (source.equals(target)) {
        counted.self++;
        return;
      }
      final TypeRow to = base.types().get(target.value());
      counted.add(from, to, target);
      union.add(from, to, target);
    }

    ExcludedConstructs result(UnitCensusPass.Result census) {
      final Map<String, Construct> constructs = new LinkedHashMap<>();
      pairs.forEach(
          (construct, counted) -> {
            final UnitCensusPass.ConstructCount seen = census.constructs().get(construct);
            final long added = counted.added(base);
            final long internal = counted.addedInternal(base);
            constructs.put(
                construct.name(),
                new Construct(
                    seen.sites(),
                    seen.withoutTarget(),
                    seen.droppedTarget(),
                    counted.outside,
                    counted.occurrences,
                    counted.self,
                    counted.size(),
                    added,
                    internal,
                    percentOfBase(internal)));
          });
      final long unionInternal = union.addedInternal(base);
      final Map<String, Integer> drops = new LinkedHashMap<>();
      census.scanDrops().forEach((reason, count) -> drops.put(reason.name(), count));
      return new ExcludedConstructs(
          base.basePairs().length,
          base.baseInternalPairs(),
          constructs,
          new Union(union.added(base), unionInternal, percentOfBase(unionInternal)),
          census.staticFieldWrites(),
          drops,
          census.scanCrashes(),
          census.scanNanos() / 1_000_000);
    }

    private double percentOfBase(long internal) {
      return base.baseInternalPairs() == 0
          ? 0
          : hundredths(PERCENT * internal / base.baseInternalPairs());
    }
  }

  private static final class Pairs {

    private final Set<Long> internal = new HashSet<>();
    private final Set<Long> external = new HashSet<>();
    private final Set<String> unwritten = new HashSet<>();
    private long outside;
    private long occurrences;
    private long self;

    private void add(TypeRow from, TypeRow to, CodeElementId target) {
      if (to == null) {
        unwritten.add(from.pk() + " " + target.value());
      } else if (to.external()) {
        external.add(pair(from.pk(), to.pk()));
      } else {
        internal.add(pair(from.pk(), to.pk()));
      }
    }

    private long size() {
      return internal.size() + external.size() + unwritten.size();
    }

    private long added(CensusBase base) {
      return addedInternal(base) + notIn(external, base) + unwritten.size();
    }

    private long addedInternal(CensusBase base) {
      return notIn(internal, base);
    }

    private static long notIn(Set<Long> keys, CensusBase base) {
      return keys.stream().filter(key -> Arrays.binarySearch(base.basePairs(), key) < 0).count();
    }
  }
}
