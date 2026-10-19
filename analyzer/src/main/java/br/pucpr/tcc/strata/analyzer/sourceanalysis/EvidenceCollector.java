package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class EvidenceCollector {

  private static final Comparator<RowKey> ROW_ORDER =
      Comparator.comparing(RowKey::source)
          .thenComparing(RowKey::target)
          .thenComparing(RowKey::type);

  private static final Comparator<Occurrence> EVIDENCE_ORDER =
      Comparator.comparingInt((Occurrence occurrence) -> occurrence.line() == 0 ? 1 : 0)
          .thenComparingInt(Occurrence::line)
          .thenComparing(Occurrence::fromMethod, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingInt(Occurrence::offset);

  private final String filePath;
  private final Map<RowKey, List<Occurrence>> rows = new TreeMap<>(ROW_ORDER);

  EvidenceCollector(String filePath) {
    this.filePath = Objects.requireNonNull(filePath, "filePath");
  }

  void add(Occurrence occurrence) {
    Objects.requireNonNull(occurrence, "occurrence");
    rows.computeIfAbsent(RowKey.of(occurrence), key -> new ArrayList<>()).add(occurrence);
  }

  List<ExtractedDependency> collect() {
    final List<ExtractedDependency> collected = new ArrayList<>(rows.size());
    rows.forEach((key, occurrences) -> collected.add(row(key, occurrences)));
    return List.copyOf(collected);
  }

  Map<CodeElementId, ResolutionCounts> counts() {
    final Map<CodeElementId, Tally> tallies = new HashMap<>();
    rows.forEach(
        (key, occurrences) -> {
          final Tally tally = tallies.computeIfAbsent(key.source(), _ -> new Tally());
          occurrences.forEach(tally::add);
        });
    final Map<CodeElementId, ResolutionCounts> counts = new HashMap<>();
    tallies.forEach((source, tally) -> counts.put(source, tally.counts()));
    return counts;
  }

  private ExtractedDependency row(RowKey key, List<Occurrence> occurrences) {
    ResolutionQuality resolution = occurrences.getFirst().resolution();
    boolean ambiguous = false;
    for (final Occurrence occurrence : occurrences) {
      resolution = ResolutionQuality.weakest(resolution, occurrence.resolution());
      ambiguous |= occurrence.ambiguous();
    }
    final List<DependencyEvidence> evidence =
        occurrences.stream()
            .sorted(EVIDENCE_ORDER)
            .limit(AnalysisConstants.DEPENDENCY_EVIDENCE_CAP)
            .map(EvidenceCollector::evidence)
            .toList();
    return new ExtractedDependency(
        new Dependency(key.source(), key.target(), key.type(), resolution, occurrences.size()),
        filePath,
        occurrences.getFirst().targetDeclared(),
        ambiguous,
        evidence);
  }

  private static DependencyEvidence evidence(Occurrence occurrence) {
    return new DependencyEvidence(
        occurrence.fromMethod(), occurrence.toMethod(), occurrence.line(), occurrence.resolution());
  }

  private static final class Tally {

    private long resolved;
    private long inferred;
    private long unresolved;
    private long ambiguous;

    void add(Occurrence occurrence) {
      switch (occurrence.resolution()) {
        case RESOLVED -> resolved++;
        case INFERRED -> inferred++;
        case UNRESOLVED_EXTERNAL -> unresolved++;
      }
      if (occurrence.ambiguous()) {
        ambiguous++;
      }
    }

    ResolutionCounts counts() {
      return new ResolutionCounts(resolved, inferred, unresolved, ambiguous);
    }
  }

  private record RowKey(CodeElementId source, CodeElementId target, DependencyType type) {

    static RowKey of(Occurrence occurrence) {
      return new RowKey(occurrence.source(), occurrence.target(), occurrence.type());
    }
  }

  record Occurrence(
      CodeElementId source,
      CodeElementId target,
      DependencyType type,
      ResolutionQuality resolution,
      boolean targetDeclared,
      boolean ambiguous,
      int line,
      int offset,
      CodeElementId fromMethod,
      CodeElementId toMethod) {

    Occurrence {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(resolution, "resolution");
    }
  }
}
