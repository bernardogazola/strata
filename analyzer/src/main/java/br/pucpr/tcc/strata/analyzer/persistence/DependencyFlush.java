package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyDraft.EvidenceDraft;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DependencyRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.EvidenceRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ModuleDependencyDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ModuleDependencyRow;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchStructure;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

final class DependencyFlush {

  private final long analysisPk;
  private final Map<CodeElementId, Long> numbered;
  private final Map<CodeElementId, Long> methods;
  private final Map<CodeElementId, Long> index;
  private final Map<CodeElementId, Long> externals;
  private final ToLongFunction<String> filePks;

  DependencyFlush(
      long analysisPk,
      Map<CodeElementId, Long> numbered,
      Map<CodeElementId, Long> methods,
      Map<CodeElementId, Long> index,
      Map<CodeElementId, Long> externals,
      ToLongFunction<String> filePks) {
    this.analysisPk = analysisPk;
    this.numbered = numbered;
    this.methods = methods;
    this.index = index;
    this.externals = externals;
    this.filePks = filePks;
  }

  Outcome write(Connection connection, List<DependencyDraft> pending, List<BatchStructure> batches)
      throws SQLException {
    final List<DependencyDraft> drafts = new ArrayList<>(pending);
    for (final BatchStructure batch : batches) {
      for (final ExtractedDependency extracted : batch.dependencies().dependencies()) {
        drafts.add(draft(extracted));
      }
    }
    final Map<CodeElementId, Long> newExternals =
        insertExternals(connection, missingExternals(drafts));
    final List<DependencyDraft> resolvable = new ArrayList<>(drafts.size());
    final List<Long> targets = new ArrayList<>(drafts.size());
    final List<DependencyDraft> stillPending = new ArrayList<>();
    for (final DependencyDraft draft : drafts) {
      final Long target = targetPk(draft, newExternals);
      if (target == null) {
        stillPending.add(draft);
      } else {
        resolvable.add(draft);
        targets.add(target);
      }
    }
    insertDependencies(connection, resolvable, targets);
    insertModules(connection, batches);
    return new Outcome(newExternals, stillPending);
  }

  private DependencyDraft draft(ExtractedDependency extracted) {
    final Dependency row = extracted.dependency();
    requireValidPair(row, extracted.targetDeclared());
    final long filePk = filePks.applyAsLong(extracted.filePath());
    final List<EvidenceDraft> evidence = new ArrayList<>(extracted.evidence().size());
    for (final DependencyEvidence occurrence : extracted.evidence()) {
      if (occurrence.toMethod() != null) {
        throw new IllegalStateException(
            "to_method_pk ainda não foi gravado: " + occurrence.toMethod());
      }
      final Long fromMethodPk =
          occurrence.fromMethod() == null ? null : methods.get(occurrence.fromMethod());
      final Integer line = occurrence.line() == 0 ? null : occurrence.line();
      evidence.add(new EvidenceDraft(fromMethodPk, line, occurrence.resolution()));
    }
    return new DependencyDraft(
        sourcePk(row.source()),
        row.target(),
        extracted.targetDeclared(),
        row.type(),
        row.resolution(),
        row.occurrences(),
        DependencyAggregator.rowWeight(row),
        filePk,
        evidence);
  }

  private static void requireValidPair(Dependency row, boolean targetDeclared) {
    final ResolutionQuality invalid =
        targetDeclared ? ResolutionQuality.UNRESOLVED_EXTERNAL : ResolutionQuality.INFERRED;
    if (row.resolution() == invalid) {
      throw new IllegalStateException(
          "um alvo " + (targetDeclared ? "declarado" : "externo") + " nunca é " + invalid);
    }
  }

  private long sourcePk(CodeElementId source) {
    final Long pk = numbered.getOrDefault(source, index.get(source));
    if (pk == null) {
      throw new IllegalStateException("uma origem de dependência que nunca foi gravada: " + source);
    }
    return pk;
  }

  private SortedSet<CodeElementId> missingExternals(List<DependencyDraft> drafts) {
    final SortedSet<CodeElementId> missing = new TreeSet<>();
    for (final DependencyDraft draft : drafts) {
      if (!draft.targetDeclared() && !externals.containsKey(draft.target())) {
        missing.add(draft.target());
      }
    }
    return missing;
  }

  private Map<CodeElementId, Long> insertExternals(
      Connection connection, SortedSet<CodeElementId> missing) throws SQLException {
    final Map<CodeElementId, Long> inserted = new HashMap<>();
    if (missing.isEmpty()) {
      return inserted;
    }
    final List<ElementRow> rows = new ArrayList<>(missing.size());
    long next = ElementDao.nextId(connection);
    for (final CodeElementId id : missing) {
      if (numbered.containsKey(id) || index.containsKey(id)) {
        throw new IllegalStateException("um alvo externo é um tipo do repositório: " + id);
      }
      if (!id.fitsCeiling()) {
        throw new IllegalStateException("um id externo acima do teto: " + id.utf8Length());
      }
      inserted.put(id, next);
      rows.add(externalRow(next++, id));
    }
    ElementDao.insert(connection, analysisPk, rows);
    return inserted;
  }

  private static ElementRow externalRow(long pk, CodeElementId id) {
    final String body = id.sortKey();
    final int separator = Math.max(body.lastIndexOf('.'), body.lastIndexOf('$'));
    return new ElementRow(
        pk,
        id,
        ElementKind.TYPE,
        null,
        label(body.substring(separator + 1)),
        body,
        body,
        null,
        "",
        true,
        false,
        null,
        null,
        null,
        null);
  }

  private static String label(String name) {
    if (name.codePointCount(0, name.length()) <= AnalysisConstants.MAX_LABEL_CHARS) {
      return name;
    }
    return name.substring(0, name.offsetByCodePoints(0, AnalysisConstants.MAX_LABEL_CHARS));
  }

  private Long targetPk(DependencyDraft draft, Map<CodeElementId, Long> newExternals) {
    final CodeElementId target = draft.target();
    if (draft.targetDeclared()) {
      return numbered.getOrDefault(target, index.get(target));
    }
    return externals.getOrDefault(target, newExternals.get(target));
  }

  private void insertDependencies(
      Connection connection, List<DependencyDraft> drafts, List<Long> targets) throws SQLException {
    if (drafts.isEmpty()) {
      return;
    }
    final List<DependencyRow> rows = new ArrayList<>(drafts.size());
    final List<EvidenceRow> evidence = new ArrayList<>();
    long next = DependencyDao.nextId(connection);
    for (int i = 0; i < drafts.size(); i++) {
      final DependencyDraft draft = drafts.get(i);
      final long pk = next++;
      rows.add(
          new DependencyRow(
              pk,
              draft.sourcePk(),
              targets.get(i),
              draft.type(),
              draft.resolution(),
              draft.occurrences(),
              draft.weight()));
      for (final EvidenceDraft occurrence : draft.evidence()) {
        evidence.add(
            new EvidenceRow(
                pk,
                occurrence.fromMethodPk(),
                null,
                draft.filePk(),
                occurrence.line(),
                occurrence.resolution()));
      }
    }
    DependencyDao.insert(connection, analysisPk, rows);
    EvidenceDao.insert(connection, evidence);
  }

  private void insertModules(Connection connection, List<BatchStructure> batches)
      throws SQLException {
    final Map<ModuleKey, ModuleDependency> unique = new LinkedHashMap<>();
    for (final BatchStructure batch : batches) {
      for (final ModuleDependency module : batch.dependencies().modules()) {
        final long filePk = filePks.applyAsLong(module.filePath());
        unique.putIfAbsent(new ModuleKey(filePk, module.requiredName()), module);
      }
    }
    if (unique.isEmpty()) {
      return;
    }
    final List<ModuleDependencyRow> rows = new ArrayList<>(unique.size());
    long next = ModuleDependencyDao.nextId(connection);
    for (final Map.Entry<ModuleKey, ModuleDependency> entry : unique.entrySet()) {
      final ModuleDependency module = entry.getValue();
      rows.add(
          new ModuleDependencyRow(
              next++,
              entry.getKey().filePk(),
              module.moduleName(),
              module.requiredName(),
              module.transitive(),
              module.isStatic(),
              module.line() == 0 ? null : module.line()));
    }
    ModuleDependencyDao.insert(connection, analysisPk, rows);
  }

  record Outcome(Map<CodeElementId, Long> newExternals, List<DependencyDraft> stillPending) {}

  private record ModuleKey(long filePk, String requiredName) {}
}
