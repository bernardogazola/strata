package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.persistence.ElementWriter;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionCounts;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceIssue;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class SourceAnalysisStage implements StageExecutor {

  private static final String DROP_PREFIX = "drop";

  private final SpoonModelBuilder builder;

  public SourceAnalysisStage(SpoonModelBuilder builder) {
    this.builder = Objects.requireNonNull(builder, "builder");
  }

  @Override
  public StageOutcome execute(StageContext context) throws SQLException {
    final RunInputs inputs = context.inputs();
    final List<SourceRoot> roots = inputs.selectedRoots();
    final long total = roots.stream().mapToLong(SourceRoot::javaFileCount).sum();
    final BooleanSupplier cancelled = context.cancellation().asSupplier();
    final ElementWriter writer =
        new ElementWriter(
            context.connections(),
            context.analysisPk(),
            roots,
            inputs.repository().snapshot().inventory().javaSources(),
            cancelled);
    final StructureExtractor extractor = new StructureExtractor(roots, writer);
    context.progress().report(ProgressUnit.JAVA_FILES, 0, total);
    final SourceAnalysis analysis =
        builder.analyze(
            inputs.repository(),
            roots,
            new SourceProgressVisitor(extractor, context.progress(), total),
            cancelled);
    context.cancellation().throwIfCancelled();
    warn(context.warnings(), analysis.issues());
    warn(context.warnings(), extractor.issues());
    context
        .completeness()
        .recordSourceFiles(
            analysis.totalFiles(), analysis.skippedFiles(), analysis.parseFailures());
    final long types =
        context
            .connections()
            .read(connection -> ElementDao.countInternalTypes(connection, context.analysisPk()));
    return StageOutcome.completed(counters(analysis, extractor, writer, types));
  }

  private static void warn(WarningSink warnings, List<SourceIssue> issues) {
    for (final SourceIssue issue : issues) {
      warnings.warn(ErrorCodes.source(issue));
    }
  }

  private static StageCounters counters(
      SourceAnalysis analysis, StructureExtractor extractor, ElementWriter writer, long types) {
    final Map<String, Long> values = new LinkedHashMap<>();
    values.put("files", (long) analysis.totalFiles());
    values.put("types", types);
    final ResolutionCounts resolution = extractor.resolutionCounts();
    values.put("resolvedRefs", resolution.resolved());
    values.put("inferredRefs", resolution.inferred());
    values.put("unresolvedRefs", resolution.unresolved());
    values.put("ambiguousRefs", resolution.ambiguous());
    extractor
        .droppedOccurrences()
        .forEach((reason, occurrences) -> values.put(dropKey(reason), occurrences));
    values.put("droppedDeclaredTargets", (long) writer.droppedDependencies());
    values.put("unnumberedCallees", (long) writer.unnumberedCallees());
    values.put("duplicateElements", extractor.duplicateElements());
    values.put("elementIdsTooLong", extractor.elementIdsTooLong());
    return StageCounters.of(values);
  }

  static String dropKey(StructureExtractor.DropReason reason) {
    final StringBuilder key = new StringBuilder(DROP_PREFIX);
    for (final String word : reason.name().split("_")) {
      key.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
    }
    return key.toString();
  }
}
