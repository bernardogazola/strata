package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEvidence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ModuleDependency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecordingStructureSink implements StructureSink {

  static final Comparator<ExtractedDependency> ROW_ORDER =
      Comparator.comparing((ExtractedDependency row) -> row.dependency().source())
          .thenComparing(row -> row.dependency().target())
          .thenComparing(row -> row.dependency().type());

  private final List<SourceFile> begun = new ArrayList<>();
  private final List<BatchStructure> accepted = new ArrayList<>();
  private final List<SourceFile> ended = new ArrayList<>();
  private final List<FileElement> fileElements = new ArrayList<>();
  private int ends;

  @Override
  public void begin(List<SourceFile> files) {
    begun.addAll(files);
  }

  @Override
  public void accept(BatchStructure batch) {
    accepted.add(batch);
  }

  @Override
  public void accept(BatchElements elements) {
    throw new AssertionError(
        "StructureExtractor deve entregar um lote por accept(BatchStructure), nunca por"
            + " accept(BatchElements): "
            + elements.size()
            + " elementos chegaram sem as suas dependências");
  }

  @Override
  public void end(List<SourceFile> files, List<FileElement> elements) {
    ends++;
    ended.addAll(files);
    fileElements.addAll(elements);
  }

  List<SourceFile> begun() {
    return List.copyOf(begun);
  }

  List<BatchStructure> accepted() {
    return List.copyOf(accepted);
  }

  List<SourceFile> ended() {
    return List.copyOf(ended);
  }

  int ends() {
    return ends;
  }

  List<ExtractedDependency> dependencies() {
    return accepted.stream()
        .flatMap(batch -> batch.dependencies().dependencies().stream())
        .toList();
  }

  List<ModuleDependency> modules() {
    return accepted.stream().flatMap(batch -> batch.dependencies().modules().stream()).toList();
  }

  List<ExtractedDependency> rows() {
    return dependencies().stream().sorted(ROW_ORDER).toList();
  }

  List<String> tuples() {
    return rows().stream().map(RecordingStructureSink::tuple).toList();
  }

  ExtractedDependency row(String from, String to, String type) {
    final String head = from + " -> " + to + " " + type + " ";
    return rows().stream()
        .filter(row -> tuple(row).startsWith(head))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhuma linha " + head + "em " + tuples()));
  }

  static String tuple(ExtractedDependency row) {
    final Dependency dependency = row.dependency();
    return dependency.source().sortKey()
        + " -> "
        + dependency.target().sortKey()
        + " "
        + dependency.type()
        + " x"
        + dependency.occurrences()
        + " "
        + dependency.resolution()
        + (row.targetDeclared() ? "" : " external")
        + (row.ambiguous() ? " ambiguous" : "");
  }

  static List<String> evidence(ExtractedDependency row) {
    return row.evidence().stream().map(RecordingStructureSink::evidence).toList();
  }

  private static String evidence(DependencyEvidence evidence) {
    return "L"
        + evidence.line()
        + " "
        + (evidence.fromMethod() == null ? "-" : evidence.fromMethod().value())
        + (evidence.toMethod() == null ? "" : " toMethod=" + evidence.toMethod().value());
  }

  static String module(ModuleDependency module) {
    return module.moduleName()
        + " -> "
        + module.requiredName()
        + (module.transitive() ? " transitive" : "")
        + (module.isStatic() ? " static" : "")
        + " L"
        + module.line()
        + " "
        + module.filePath();
  }
}
